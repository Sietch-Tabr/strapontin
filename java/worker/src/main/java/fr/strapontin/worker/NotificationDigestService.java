/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.UtilitairesChaine;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de notification digest (email + push VAPID) pour le worker.
///
/// Lit les `user_matches` non notifiés, groupe par utilisateur, envoie email via Resend et push
/// VAPID, puis met à jour `notifie_le`.
///
/// Si `RESEND_API_KEY` ou les clés VAPID sont absentes de `app_config`, la notification
/// correspondante est ignorée sans erreur. Depuis, le canal email est de même ignoré sans erreur si
/// le quota d'envoi Resend jour/mois est atteint ({@link GestionQuotaResendWorker}, compteur
/// partagé avec le module web).
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class NotificationDigestService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDigestService.class);

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;
  private final LogsAdminWorker logsAdminWorker;
  private final GestionQuotaResendWorker gestionQuotaResendWorker;
  private final FabriqueResend fabriqueResend;
  private final FabriquePushService fabriquePushService;
  // Bilan de panne push agrégé sur le cycle de digest courant, remonté dans logs_admin.
  private final CompteurBilanFetch compteurPush = new CompteurBilanFetch();

  /// Fabrique du client Resend — indirection nécessaire pour rendre {@code envoyerEmail} testable
  /// sur son chemin nominal de succès, le SDK Resend n'étant pas injectable directement.
  @FunctionalInterface
  interface FabriqueResend {
    Resend creer(String cle);
  }

  /// Fabrique du client push VAPID — même motif que {@link FabriqueResend}.
  @FunctionalInterface
  interface FabriquePushService {
    PushService creer(String clePublique, String clePrivee, String sujet)
        throws java.security.GeneralSecurityException;
  }

  /// Constructeur Spring — : les fabriques Resend/PushService ne sont explicitement annotées
  /// {@code @Autowired} que sur ce constructeur, sans quoi Spring ne peut pas déterminer lequel des
  /// deux constructeurs utiliser dès qu'il en existe plus d'un.
  @org.springframework.beans.factory.annotation.Autowired
  public NotificationDigestService(
      ConfigServiceWorker configService,
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper jackson,
      LogsAdminWorker logsAdminWorker,
      GestionQuotaResendWorker gestionQuotaResendWorker) {
    this(
        configService,
        jdbc,
        jackson,
        logsAdminWorker,
        gestionQuotaResendWorker,
        Resend::new,
        PushService::new);
  }

  /// Constructeur de test — permet d'injecter des fabriques Resend/PushService mockables pour
  /// exercer le chemin nominal de succès de l'envoi email/push.
  NotificationDigestService(
      ConfigServiceWorker configService,
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper jackson,
      LogsAdminWorker logsAdminWorker,
      GestionQuotaResendWorker gestionQuotaResendWorker,
      FabriqueResend fabriqueResend,
      FabriquePushService fabriquePushService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.jackson = jackson;
    this.logsAdminWorker = logsAdminWorker;
    this.gestionQuotaResendWorker = gestionQuotaResendWorker;
    this.fabriqueResend = fabriqueResend;
    this.fabriquePushService = fabriquePushService;
  }

  /// Données d'un concert pour le digest.
  ///
  /// @param artiste nom de l'artiste
  /// @param date date ISO (yyyy-MM-dd)
  /// @param salle nom de la salle
  /// @param ville ville (peut être nulle)
  /// @param url URL de billetterie (peut être nulle)
  /// @param concertId UUID du concert (string)
  /// @param artisteNorm nom de l'artiste normalisé (identique à `artiste_norm` en base)
  /// @param source origine du match : `"ARTISTE"` ou `"SALLE"`
  public record ConcertNotif(
      String artiste,
      String date,
      String salle,
      String ville,
      String url,
      String concertId,
      String artisteNorm,
      String source,
      String statut) {}

  /// Résultat d'un envoi de digest, avec le sous-ensemble de concerts effectivement tenté par canal
  /// — nécessaire pour ne marquer {@code notification_envoyee_le} que sur les concerts réellement
  /// délivrés par un canal en succès, pas sur l'ensemble du digest dès qu'un seul canal a réussi.
  private record ResultatDigest(
      boolean emailOk,
      boolean pushOk,
      List<ConcertNotif> concertsEmail,
      List<ConcertNotif> concertsPush) {}

  private record ConfigNotifUser(
      boolean notifArtistes,
      boolean notifSalles,
      boolean notifArtistesPush,
      boolean notifSallesPush,
      String frequenceNotifications) {}

  /// Parcourt les matches non notifiés pour une fréquence donnée, envoie le digest, met à jour
  /// `notifie_le`.
  ///
  /// @param frequence `"temps_reel"` (après chaque recalcul) ou `"quotidien"` (cron 8h)
  public void executerDigest(String frequence) {
    log.info("notifier:digest démarré frequence={}", frequence);
    long debut = System.currentTimeMillis();
    int nbUtilisateurs = 0;
    int nbMatchesNotifies = 0;
    int nbEmailsOk = 0;
    int nbPushOk = 0;
    int nbErreurs = 0;
    String erreur = null;

    try {
      MatchesGroupes groupes = chargerMatchesNonNotifies(frequence);

      if (groupes.parUtilisateur().isEmpty()) {
        log.info("notifier:digest — aucun match non notifié");
        return;
      }

      Timestamp maintenant = Timestamp.from(Instant.now());
      for (Map.Entry<String, List<ConcertNotif>> entree : groupes.parUtilisateur().entrySet()) {
        String userId = entree.getKey();
        List<ConcertNotif> concerts = entree.getValue();
        Set<UUID> concertIdsNotifies = new LinkedHashSet<>();
        try {
          ResultatDigest res =
              envoyerDigest(
                  userId,
                  groupes.emailsUtilisateurs().get(userId),
                  concerts,
                  groupes.configsUtilisateurs().get(userId));
          if (res.emailOk()) nbEmailsOk++;
          if (res.pushOk()) nbPushOk++;
          // Seuls les concerts du canal effectivement en succès sont marqués notifiés :
          // un digest mixte email+push où un seul canal échoue ne doit pas marquer à tort les
          // concerts du canal en échec comme notifiés.
          if (res.emailOk()) {
            res.concertsEmail()
                .forEach(c -> concertIdsNotifies.add(UUID.fromString(c.concertId())));
          }
          if (res.pushOk()) {
            res.concertsPush().forEach(c -> concertIdsNotifies.add(UUID.fromString(c.concertId())));
          }
        } catch (Exception e) {
          nbErreurs++;
          log.error("notifier:digest erreur envoi pour user={} : {}", userId, e.getMessage());
        }
        if (!concertIdsNotifies.isEmpty()) {
          int updated =
              jdbc.update(
                  """
                  UPDATE user_matches um
                  SET notification_envoyee_le = :maintenant, statut_notifie = c.statut
                  FROM concerts c
                  WHERE c.id = um.concert_id
                    AND um.user_id = :userId
                    AND um.concert_id IN (:concertIds)
                  """,
                  new MapSqlParameterSource()
                      .addValue("userId", userId)
                      .addValue("maintenant", maintenant)
                      .addValue("concertIds", concertIdsNotifies));
          nbMatchesNotifies += updated;
        } else {
          log.warn(
              "notifier:digest — notification échouée pour user={}, notification_envoyee_le non mis"
                  + " à jour",
              userId);
        }
        nbUtilisateurs++;
      }
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("notifier:digest échoué", e);
    } finally {
      enregistrerStat(System.currentTimeMillis() - debut, nbMatchesNotifies, erreur);
      logAdminDigest(nbEmailsOk, nbPushOk, nbErreurs);
      remonterBilanPush();
      log.info("notifier:digest terminé users={} concerts={}", nbUtilisateurs, nbMatchesNotifies);
    }
  }

  /// Matches non notifiés regroupés par utilisateur, prêts pour l'envoi du digest.
  ///
  /// @param emailsUtilisateurs email de chaque utilisateur concerné
  /// @param configsUtilisateurs préférences de notification de chaque utilisateur
  /// @param parUtilisateur concerts non notifiés, groupés par utilisateur
  private record MatchesGroupes(
      Map<String, String> emailsUtilisateurs,
      Map<String, ConfigNotifUser> configsUtilisateurs,
      Map<String, List<ConcertNotif>> parUtilisateur) {}

  /// Charge et regroupe par utilisateur les matches non notifiés pour une fréquence donnée (extrait
  /// de {@link #executerDigest}).
  ///
  /// @param frequence `"temps_reel"` ou `"quotidien"`
  /// @return les matches non notifiés, groupés par utilisateur
  private MatchesGroupes chargerMatchesNonNotifies(String frequence) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT um.user_id, u.email, CAST(c.id AS text) AS concert_id,
                   COALESCE(a.nom, c.artiste) AS artiste,
                   CAST(c.date AS text) AS date, c.salle, c.ville, c.url, c.artiste_norm,
                   c.statut,
                   uc.notif_artistes, uc.notif_salles,
                   uc.notif_artistes_push, uc.notif_salles_push,
                   uc.frequence_notifications::text AS frequence_notifications,
                   CASE
                     WHEN EXISTS (
                       SELECT 1 FROM user_artistes ua
                       WHERE ua.user_id = um.user_id
                         AND c.artiste_id = ua.artiste_id
                     ) THEN 'ARTISTE'
                     ELSE 'SALLE'
                   END AS source
            FROM user_matches um
            JOIN concerts    c  ON c.id      = um.concert_id
            LEFT JOIN artistes a ON a.id = c.artiste_id
            JOIN users       u  ON u.id      = um.user_id
            JOIN user_config uc ON uc.user_id = um.user_id
            WHERE um.notification_envoyee_le IS NULL
              AND um.masque_le  IS NULL
              AND (
                (uc.frequence_notifications = CAST(:frequence AS frequence_notif)
                 AND (
                   ((uc.notif_artistes = true OR uc.notif_artistes_push = true) AND EXISTS (
                     SELECT 1 FROM user_artistes ua
                     WHERE ua.user_id = um.user_id
                       AND c.artiste_id = ua.artiste_id
                   ))
                   OR
                   ((uc.notif_salles = true OR uc.notif_salles_push = true) AND EXISTS (
                     SELECT 1 FROM user_salles us
                     WHERE us.user_id = um.user_id
                       AND c.salle_id = us.salle_id
                   ))
                 ))
                OR
                (CAST(:frequence AS frequence_notif) = 'temps_reel'
                 AND uc.frequence_notifications = 'jamais'
                 AND (
                   (uc.notif_artistes_push = true AND EXISTS (
                     SELECT 1 FROM user_artistes ua
                     WHERE ua.user_id = um.user_id
                       AND c.artiste_id = ua.artiste_id
                   ))
                   OR
                   (uc.notif_salles_push = true AND EXISTS (
                     SELECT 1 FROM user_salles us
                     WHERE us.user_id = um.user_id
                       AND c.salle_id = us.salle_id
                   ))
                 ))
              )
            ORDER BY um.user_id, c.date
            """,
            Map.of("frequence", frequence));

    Map<String, String> emailsUtilisateurs = new LinkedHashMap<>();
    Map<String, ConfigNotifUser> configsUtilisateurs = new LinkedHashMap<>();
    Map<String, List<ConcertNotif>> parUtilisateur = new LinkedHashMap<>();
    for (Map<String, Object> ligne : lignes) {
      String userId = (String) ligne.get("user_id");
      emailsUtilisateurs.putIfAbsent(userId, (String) ligne.get("email"));
      configsUtilisateurs.putIfAbsent(
          userId,
          new ConfigNotifUser(
              Boolean.TRUE.equals(ligne.get("notif_artistes")),
              Boolean.TRUE.equals(ligne.get("notif_salles")),
              Boolean.TRUE.equals(ligne.get("notif_artistes_push")),
              Boolean.TRUE.equals(ligne.get("notif_salles_push")),
              (String) ligne.get("frequence_notifications")));
      parUtilisateur
          .computeIfAbsent(userId, _ -> new ArrayList<>())
          .add(
              new ConcertNotif(
                  (String) ligne.get("artiste"),
                  (String) ligne.get("date"),
                  (String) ligne.get("salle"),
                  (String) ligne.get("ville"),
                  (String) ligne.get("url"),
                  (String) ligne.get("concert_id"),
                  (String) ligne.get("artiste_norm"),
                  (String) ligne.get("source"),
                  (String) ligne.get("statut")));
    }
    return new MatchesGroupes(emailsUtilisateurs, configsUtilisateurs, parUtilisateur);
  }

  /// Remonte dans `logs_admin` le bilan agrégé des envois push pour le cycle de digest qui vient de
  /// se terminer, même mécanisme générique que le bilan de panne des sources de concerts (voir
  /// {@code WorkerStatsService#remonterBilan}).
  private void remonterBilanPush() {
    BilanFetch bilan = compteurPush.preleverBilan();
    bilan
        .evaluer("notifier:digest", "Push", BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT)
        .ifPresent(
            entree ->
                logsAdminWorker.inserer(
                    entree.niveau(), "worker", entree.message(), entree.meta()));
  }

  private ResultatDigest envoyerDigest(
      String userId, String email, List<ConcertNotif> concerts, ConfigNotifUser config) {
    boolean emailActif = !"jamais".equals(config.frequenceNotifications());
    List<ConcertNotif> concertsEmail =
        emailActif
            ? concerts.stream()
                .filter(
                    c ->
                        ("ARTISTE".equals(c.source()) && config.notifArtistes())
                            || ("SALLE".equals(c.source()) && config.notifSalles()))
                .toList()
            : List.of();
    List<ConcertNotif> concertsPush =
        concerts.stream()
            .filter(
                c ->
                    ("ARTISTE".equals(c.source()) && config.notifArtistesPush())
                        || ("SALLE".equals(c.source()) && config.notifSallesPush()))
            .toList();
    boolean emailOk = envoyerEmail(email, concertsEmail);
    boolean pushOk = envoyerPush(userId, concertsPush);
    return new ResultatDigest(emailOk, pushOk, concertsEmail, concertsPush);
  }

  private boolean envoyerEmail(String email, List<ConcertNotif> concerts) {
    if (concerts.isEmpty()) return false;
    Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      log.debug("RESEND_API_KEY absent — digest email ignoré");
      return false;
    }
    if (!gestionQuotaResendWorker.reserverEnvoi()) {
      // notification_envoyee_le n'est pas marquée pour ce canal (comportement déjà
      // existant, cf. clé "RESEND_API_KEY absent" ci-dessus) — retenté au prochain cycle de
      // digest plutôt que perdu.
      return false;
    }
    String expediteur = configService.lireConfig("CONTACT_EMAIL").orElse("");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");

    int nb = concerts.size();
    String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);
    try {
      Resend resend = fabriqueResend.creer(cle.get());
      CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(expediteur)
              .replyTo(expediteur)
              .to(List.of(email))
              .subject(sujet)
              .html(construireCorpsEmail(sujet, concerts, baseUrl))
              .text(sujet)
              .build();
      resend.emails().send(options);
      log.info("Digest email envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi digest email : {}", email, e);
      return false;
    }
  }

  /// Issue possible d'un envoi push individuel, selon le statut HTTP retourné par le push service.
  enum ResultatEnvoiPush {
    SUCCES,
    ABONNEMENT_MORT,
    ECHEC
  }

  /// Interprète le statut HTTP d'un envoi push.
  ///
  /// <p>Seul un statut 2xx est un succès. 404/410 signent un abonnement mort (à supprimer). Tout le
  /// reste (400, 429, 5xx…) est un échec — avant, seuls 404/410 étaient exclus du succès, comptant
  /// à tort tout autre code d'erreur comme un envoi réussi.
  ///
  /// @param status statut HTTP retourné par le push service
  /// @return issue de l'envoi
  static ResultatEnvoiPush interpreterStatutPush(int status) {
    if (status == 404 || status == 410) return ResultatEnvoiPush.ABONNEMENT_MORT;
    if (status >= 200 && status < 300) return ResultatEnvoiPush.SUCCES;
    return ResultatEnvoiPush.ECHEC;
  }

  private boolean envoyerPush(String userId, List<ConcertNotif> concerts) {
    if (concerts.isEmpty()) return false;
    Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      log.debug("VAPID non configuré — push digest ignoré pour {}", userId);
      return false;
    }
    List<Map<String, Object>> abonnements =
        jdbc.queryForList(
            "SELECT id, endpoint, p256dh, auth FROM push_abonnements WHERE user_id = :userId",
            Map.of("userId", userId));
    if (abonnements.isEmpty()) return false;

    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    PushService pushService;
    try {
      pushService =
          fabriquePushService.creer(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      log.error("Impossible d'initialiser le service VAPID", e);
      return false;
    }

    String payload = construirePayloadPush(concerts, baseUrl);
    int nbEnvoyes = 0;
    for (Map<String, Object> ab : abonnements) {
      compteurPush.enregistrerAppel();
      try {
        String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
        String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
        Subscription subscription =
            new Subscription((String) ab.get("endpoint"), new Subscription.Keys(p256dh, auth));
        Notification notification = new Notification(subscription, payload);
        HttpResponse response = pushService.send(notification);
        int status = response.getStatusLine().getStatusCode();
        switch (interpreterStatutPush(status)) {
          case ABONNEMENT_MORT -> {
            // Abonnement mort (410/404) : nettoyage attendu, pas une panne du canal push —
            // n'entre pas dans le bilan de panne.
            jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", ab.get("id")));
            log.info("Abonnement push révoqué : {} — {}", userId, ab.get("endpoint"));
          }
          case SUCCES -> nbEnvoyes++;
          case ECHEC -> {
            // tout statut hors 2xx/404/410 (400, 429, 5xx...) est un échec, pas un
            // succès implicite — sans cette branche, un push jamais délivré était compté
            // comme réussi.
            compteurPush.enregistrerErreur();
            log.warn("Push: statut inattendu {} pour {} — {}", status, userId, ab.get("endpoint"));
          }
        }
      } catch (Exception e) {
        compteurPush.enregistrerErreur();
        log.warn("Push: échec envoi pour {} — {}", userId, ab.get("endpoint"), e);
      }
    }
    if (nbEnvoyes > 0) log.info("Push digest envoyé : {} ({} abonnement(s))", userId, nbEnvoyes);
    return nbEnvoyes > 0;
  }

  // url en chemin relatif (pas préfixé par baseUrl) — les listeners du service worker
  // (main.js/App.vue) passent cette valeur telle quelle à router.push(), qui ne matche aucune
  // route déclarée avec un schéma https:// en préfixe si PUBLIC_BASE_URL est configurée
  // (navigation muette au lieu du scroll/anchor visé). icon reste préfixé par baseUrl (ou
  // relatif si absente), résolu par le service worker contre sa propre origine dans les deux cas.
  private static String construirePayloadPush(List<ConcertNotif> concerts, String baseUrl) {
    ConcertNotif premier = concerts.get(0);
    String titre =
        concerts.size() == 1
            ? premier.artiste() + " — " + premier.salle()
            : concerts.size() + " nouveaux concerts";
    String corps =
        concerts.size() == 1
            ? ("presale".equals(premier.statut()) ? "Prévente · " : "")
                + formaterDate(premier.date())
                + (premier.ville() != null ? " · " + premier.ville() : "")
            : construireLignesPreventePush(concerts);
    String url;
    if (concerts.size() == 1) {
      if ("SALLE".equals(premier.source())) {
        url = "/spectacles?source=salles";
      } else {
        url = "/?ancre=artiste-" + URLEncoder.encode(premier.artisteNorm(), StandardCharsets.UTF_8);
      }
    } else {
      url = "/?nouveau=true";
    }
    return """
    {"title":"%s","body":"%s","icon":"%s/images/icon-192.png","url":"%s"}\
    """
        .formatted(
            UtilitairesChaine.echapperJson(titre),
            UtilitairesChaine.echapperJson(corps),
            baseUrl,
            UtilitairesChaine.echapperJson(url));
  }

  /// Construit le corps texte du push pour un digest à plusieurs concerts, une ligne par concert,
  /// en insérant une ligne vide à chaque changement d'artiste — sans cette séparation, un digest
  /// couvrant plusieurs artistes se lit comme une liste continue sans indication de regroupement.
  ///
  /// @param concerts concerts du digest (triés par date), au moins deux éléments
  /// @param baseUrl l'URL publique de base de l'application
  /// @return le corps texte du push, une ligne (éventuellement précédée d'une ligne vide) par
  ///   concert
  private static String construireLignesPreventePush(List<ConcertNotif> concerts) {
    StringBuilder corps = new StringBuilder();
    String artistePrecedent = null;
    for (ConcertNotif c : concerts) {
      if (artistePrecedent != null && !artistePrecedent.equals(c.artiste())) corps.append('\n');
      if (!corps.isEmpty()) corps.append('\n');
      corps
          .append(c.artiste())
          .append(" · ")
          .append(formaterDate(c.date()))
          .append("presale".equals(c.statut()) ? " · Prévente" : "");
      artistePrecedent = c.artiste();
    }
    return corps.toString();
  }

  private static String e(String valeur) {
    return UtilitairesChaine.echapperHtml(valeur);
  }

  private static String construireCorpsEmail(
      String sujet, List<ConcertNotif> concerts, String baseUrl) {
    StringBuilder lignes = new StringBuilder();
    String artistePrecedent = null;
    for (ConcertNotif c : concerts) {
      String lieu = c.ville() != null ? e(c.salle()) + " · " + e(c.ville()) : e(c.salle());
      String lien =
          c.url() != null
              ? " — <a href=\"%s\" style=\"color:#111;font-size:13px\">Billets →</a>"
                  .formatted(e(c.url()))
              : "";
      String prevente =
          "presale".equals(c.statut())
              ? " · <span style=\"color:#c026d3;font-weight:600\">Prévente</span>"
              : "";
      // Rupture visuelle plus marquée qu'entre deux concerts d'un même artiste — sans elle, un
      // digest couvrant plusieurs artistes se lit comme une liste continue.
      String stylecel =
          artistePrecedent != null && !artistePrecedent.equals(c.artiste())
              ? "padding:16px 0 10px;border-top:2px solid #ddd;vertical-align:top"
              : "padding:10px 0;border-bottom:1px solid #f0f0f0;vertical-align:top";
      lignes.append(
          """
          <tr>
            <td style="%s">
              <div style="font-weight:600;color:#111;font-size:14px">%s</div>
              <div style="color:#666;font-size:13px;margin-top:2px">%s%s%s</div>
              <div style="color:#999;font-size:12px;margin-top:2px">%s</div>
            </td>
          </tr>
          """
              .formatted(stylecel, e(c.artiste()), formaterDate(c.date()), prevente, lien, lieu));
      artistePrecedent = c.artiste();
    }
    return """
    <!DOCTYPE html>
    <html lang="fr">
    <head><meta charset="utf-8"><title>%s</title></head>
    <body style="font-family:system-ui,sans-serif;background:#f9f9f9;margin:0;padding:32px 16px">
    <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e5e5e5">
    <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">%s</h2>
    <p style="color:#555;font-size:14px;margin:0 0 24px">Voici les nouveaux concerts ajoutés :</p>
    <table style="border-collapse:collapse;width:100%%">
    %s
    </table>
    <p style="margin-top:24px">
      <a href="%s/spectacles"
         style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
        Voir mes spectacles →
      </a>
    </p>
    </div>
    <p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
      Strapontin — <a href="%s/parametres" style="color:#aaa">Gérer mes préférences</a>
    </p>
    </body>
    </html>
    """
        .formatted(sujet, sujet, lignes, baseUrl, baseUrl);
  }

  private void logAdminDigest(int nbEmails, int nbPush, int nbErreurs) {
    try {
      String metaJson =
          jackson.writeValueAsString(
              Map.of("nb_emails", nbEmails, "nb_push", nbPush, "nb_erreurs", nbErreurs));
      String message = "digest — " + nbEmails + " email(s), " + nbPush + " push envoyés";
      String niveau = nbErreurs > 0 ? "warn" : "info";
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          new MapSqlParameterSource()
              .addValue("ts", Instant.now().toEpochMilli())
              .addValue("niveau", niveau)
              .addValue("source", "worker")
              .addValue("message", message)
              .addValue("meta", metaJson));
    } catch (Exception e) {
      log.warn("Impossible d'écrire log admin digest : {}", e.getMessage());
    }
  }

  private void enregistrerStat(long dureeMs, int nbNotifies, String erreur) {
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", "notifier:digest");
      params.put("now", Timestamp.from(Instant.now()));
      params.put("dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE));
      params.put("nbNotifies", nbNotifies);
      params.put("erreur", erreur);
      jdbc.update(
          """
          INSERT INTO fetch_jobs (type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, 0, :nbNotifies, :erreur)
          ON CONFLICT (type_job) DO UPDATE SET
            derniere_exec  = EXCLUDED.derniere_exec,
            duree_ms       = EXCLUDED.duree_ms,
            nb_inseres     = EXCLUDED.nb_inseres,
            nb_mis_a_jour  = EXCLUDED.nb_mis_a_jour,
            erreur         = EXCLUDED.erreur
          """,
          params);
    } catch (Exception e) {
      log.warn("Impossible d'enregistrer stat notifier:digest : {}", e.getMessage());
    }
  }

  private static String formaterDate(String dateIso) {
    LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
