/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.EchecPush;
import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.UtilitairesChaine;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Envoi push VAPID du digest de notification, pour un utilisateur
 * donné — extrait de {@link NotificationDigestService}.
 * @since 2.9.1
 * @author Kwaite
 */
class NotificationPushDigestService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationPushDigestService.class);
  private static final String CLE_ENDPOINT = "endpoint";
  private static final int NB_CONCERTS_SINGULIER = 1;
  private static final String SOURCE_SALLE = "SALLE";
  private static final String SOURCE_LOGS = "notifier-digest";

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final SuiviEchecsPush suiviEchecs;
  private final FabriquePushService fabriquePushService;
  private final Clock horloge;
  // Bilan de panne push agrégé sur le cycle de digest courant, remonté dans
  // logs_admin.
  private final CompteurBilanFetch compteurPush = new CompteurBilanFetch();

  /* package-private : instancié uniquement par Spring et les tests du même
   * package. */
  NotificationPushDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final FabriquePushService fabriquePushService) {
    this(configService, jdbc, logsAdminWorker, fabriquePushService, Clock.systemUTC());
  }

  /* package-private : horloge injectable pour les tests de la plage silencieuse. */
  NotificationPushDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final FabriquePushService fabriquePushService,
      final Clock horloge) {
    this.horloge = horloge;
    this.configService = configService;
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.suiviEchecs = new SuiviEchecsPush(jdbc, logsAdminWorker);
    this.fabriquePushService = fabriquePushService;
  }

  /**
   * Remonte dans {@code logs_admin} le bilan agrégé des envois push pour le
   * cycle de digest qui vient de se terminer, même mécanisme générique que le
   * bilan de panne des sources de concerts (voir
   * {@code WorkerStatsService#remonterBilan} ).
   */
  /* package-private : appelé par NotificationDigestService (même package). */
  void remonterBilanPush() {
    final BilanFetch bilan = compteurPush.preleverBilan();
    bilan
        .evaluer("notifier:digest", "Push", BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT)
        .ifPresent(
            entree ->
                logsAdminWorker.inserer(
                    entree.niveau(), "worker", entree.message(), entree.meta()));
  }

  /* package-private : appelé par NotificationDigestService (même package). */
  boolean envoyerPush(
      final String userId, final List<NotificationDigestService.ConcertNotif> concerts) {
    if (concerts.isEmpty()) {
      return false;
    }
    final Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    final Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      LOG.debug("VAPID non configuré — push digest ignoré pour {}", userId);
      return false;
    }
    final List<Map<String, Object>> abonnements = chargerAbonnementsPush(userId);
    if (abonnements.isEmpty()) {
      return false;
    }
    // Retenu jusqu'à 7 h : la trace durable est la ligne de push_differes, le
    // concert peut donc être marqué notifié sans que le push soit perdu.
    if (PlageSilencieusePush.estActive(Instant.now(horloge))) {
      differer(userId, concerts);
      return true;
    }

    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final PushService pushService =
        creerPushServiceVapid(clePublique.get(), clePrivee.get(), contactEmail);
    if (pushService == null) {
      return false;
    }

    final String payload = construirePayloadPush(concerts, baseUrl);
    final int nbEnvoyes = envoyerPushAAbonnements(pushService, abonnements, payload, userId);
    if (nbEnvoyes > 0) {
      LOG.info("Push digest envoyé : {} ({} abonnement(s))", userId, nbEnvoyes);
    }
    return nbEnvoyes > 0;
  }

  private void differer(
      final String userId, final List<NotificationDigestService.ConcertNotif> concerts) {
    for (final NotificationDigestService.ConcertNotif concert : concerts) {
      jdbc.update(
          """
          INSERT INTO push_differes (user_id, concert_id, source)
          VALUES (:userId, CAST(:concertId AS uuid), :source)
          ON CONFLICT DO NOTHING
          """,
          new MapSqlParameterSource()
              .addValue("userId", userId)
              .addValue("concertId", concert.concertId())
              .addValue("source", concert.source()));
    }
    LOG.info(
        "Push retenu jusqu'à 7 h (plage silencieuse) : {} concert(s) pour {}",
        concerts.size(),
        userId);
  }

  /**
   * Envoie les push retenus pendant la plage silencieuse, un digest par
   * utilisateur. Sans effet dans la plage elle-même : rien ne part avant 7 h.
   * @return nombre d'utilisateurs dont les push retenus sont partis
   */
  /* package-private : appelé par NotificationDigestService (même package). */
  int envoyerDifferes() {
    if (PlageSilencieusePush.estActive(Instant.now(horloge))) {
      return 0;
    }
    jdbc.update("DELETE FROM push_differes WHERE cree_le < now() - interval '2 days'", Map.of());
    final Map<String, List<NotificationDigestService.ConcertNotif>> parUtilisateur =
        chargerDifferes();
    int envoyes = 0;
    for (final Map.Entry<String, List<NotificationDigestService.ConcertNotif>> entree :
        parUtilisateur.entrySet()) {
      final String userId = entree.getKey();
      // Sans appareil abonné, rien ne peut plus partir : la trace est abandonnée.
      if (envoyerPush(userId, entree.getValue()) || !aUnAbonnement(userId)) {
        jdbc.update("DELETE FROM push_differes WHERE user_id = :userId", Map.of("userId", userId));
        envoyes++;
      }
    }
    return envoyes;
  }

  private Map<String, List<NotificationDigestService.ConcertNotif>> chargerDifferes() {
    final Map<String, List<NotificationDigestService.ConcertNotif>> parUtilisateur =
        new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT pd.user_id, pd.source, CAST(c.id AS text) AS concert_id,
               COALESCE(a.nom, c.artiste) AS artiste, CAST(c.date AS text) AS date,
               c.salle, c.ville, c.url, c.artiste_norm, c.statut
        FROM push_differes pd
        JOIN concerts c ON c.id = pd.concert_id
        LEFT JOIN artistes a ON a.id = c.artiste_id
        ORDER BY pd.user_id, c.date
        """,
        Map.of(),
        rs -> {
          parUtilisateur
              .computeIfAbsent(rs.getString("user_id"), _ -> new ArrayList<>())
              .add(
                  new NotificationDigestService.ConcertNotif(
                      rs.getString("artiste"),
                      rs.getString("date"),
                      rs.getString("salle"),
                      rs.getString("ville"),
                      rs.getString("url"),
                      rs.getString("concert_id"),
                      rs.getString("artiste_norm"),
                      rs.getString("source"),
                      rs.getString("statut")));
        });
    return parUtilisateur;
  }

  /**
   * @param userId utilisateur
   * @return {@code true} si au moins un appareil de l'utilisateur est abonné
   */
  /* package-private : appelé par NotificationDigestService (même package). */
  boolean aUnAbonnement(final String userId) {
    return !chargerAbonnementsPush(userId).isEmpty();
  }

  /**
   * @return {@code true} si les clés VAPID sont configurées
   */
  /* package-private : appelé par NotificationDigestService (même package). */
  boolean serviceDisponible() {
    return configService.lireConfig("VAPID_PUBLIC_KEY").isPresent()
        && configService.lireConfig("VAPID_PRIVATE_KEY").isPresent();
  }

  private List<Map<String, Object>> chargerAbonnementsPush(final String userId) {
    return jdbc.queryForList(
        "SELECT id, endpoint, p256dh, auth FROM push_abonnements WHERE user_id = :userId",
        Map.of("userId", userId));
  }

  private PushService creerPushServiceVapid(
      final String clePublique, final String clePrivee, final String contactEmail) {
    try {
      return fabriquePushService.creer(clePublique, clePrivee, "mailto:" + contactEmail);
    } catch (Exception e) {
      LOG.error("Impossible d'initialiser le service VAPID", e);
      return null;
    }
  }

  private int envoyerPushAAbonnements(
      final PushService pushService,
      final List<Map<String, Object>> abonnements,
      final String payload,
      final String userId) {
    int nbEnvoyes = 0;
    for (final Map<String, Object> ab : abonnements) {
      if (!EndpointPush.estAutorise((String) ab.get(CLE_ENDPOINT))) {
        suiviEchecs.revoquer(
            ab,
            userId,
            EchecPush.CAUSE_SERVICE_NON_PRIS_EN_CHARGE,
            "endpoint non autorisé",
            SOURCE_LOGS);
        continue;
      }
      compteurPush.enregistrerAppel();
      if (envoyerAUnAbonnement(pushService, ab, payload, userId)) {
        nbEnvoyes++;
      }
    }
    return nbEnvoyes;
  }

  private boolean envoyerAUnAbonnement(
      final PushService pushService,
      final Map<String, Object> ab,
      final String payload,
      final String userId) {
    final HttpResponse response;
    try {
      final String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
      final String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
      final Subscription subscription =
          new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
      response = pushService.send(new Notification(subscription, payload), Encoding.AES128GCM);
    } catch (Exception e) {
      compteurPush.enregistrerErreur();
      suiviEchecs.echec(ab, userId, null, e.getMessage(), SOURCE_LOGS);
      return false;
    }
    final int status = response.getStatusLine().getStatusCode();
    switch (EchecPush.interpreter(status)) {
      case SUCCES -> {
        suiviEchecs.succes(ab);
        return true;
      }
      // Abonnement supprimé côté navigateur : nettoyage attendu, pas une panne
      // du canal push, il n'entre pas dans le bilan de panne.
      case ABONNEMENT_SUPPRIME ->
          suiviEchecs.revoquer(
              ab,
              userId,
              EchecPush.causeLisible(status),
              EchecPush.raison(status, null),
              SOURCE_LOGS);
      case ECHEC -> {
        compteurPush.enregistrerErreur();
        suiviEchecs.echec(
            ab,
            userId,
            status,
            EchecPush.lireCorps(() -> response.getEntity().getContent()),
            SOURCE_LOGS);
      }
    }
    return false;
  }

  // url en chemin relatif (pas préfixé par baseUrl) — les listeners du service
  // worker (main.js/App.vue) passent cette valeur telle quelle à router.push(),
  // qui ne matche aucune route déclarée avec un schéma https:// en préfixe si
  // PUBLIC_BASE_URL est configurée (navigation muette au lieu du scroll/anchor
  // visé). icon reste préfixé par baseUrl (ou relatif si absente), résolu par
  // le service worker contre sa propre origine dans les deux cas.
  private static String construirePayloadPush(
      final List<NotificationDigestService.ConcertNotif> concerts, final String baseUrl) {
    final NotificationDigestService.ConcertNotif premier = concerts.get(0);
    final String titre =
        concerts.size() == 1
            ? premier.artiste() + " — " + premier.salle()
            : concerts.size() + " nouveaux concerts";
    final String corps =
        concerts.size() == 1
            ? ("presale".equals(premier.statut()) ? "Prévente · " : "")
                + NotificationDigestService.formaterDate(premier.date())
                + (premier.ville() != null ? " · " + premier.ville() : "")
            : construireLignesPreventePush(concerts);
    final String url;
    if (concerts.size() == NB_CONCERTS_SINGULIER) {
      if (SOURCE_SALLE.equals(premier.source())) {
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

  /**
   * Construit le corps texte du push (une ligne par concert), ligne vide
   * à chaque changement d'artiste (regroupement visuel).
   * @param concerts concerts du digest (triés par date), au moins deux
   * @return le corps texte du push, une ligne par concert
   */
  private static String construireLignesPreventePush(
      final List<NotificationDigestService.ConcertNotif> concerts) {
    final StringBuilder corps = new StringBuilder();
    String artistePrecedent = null;
    for (final NotificationDigestService.ConcertNotif c : concerts) {
      if (artistePrecedent != null && !artistePrecedent.equals(c.artiste())) {
        corps.append('\n');
      }
      if (!corps.isEmpty()) {
        corps.append('\n');
      }
      corps
          .append(c.artiste())
          .append(" · ")
          .append(NotificationDigestService.formaterDate(c.date()))
          .append("presale".equals(c.statut()) ? " · Prévente" : "");
      artistePrecedent = c.artiste();
    }
    return corps.toString();
  }
}
