/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.UtilitairesChaine;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private static final int STATUT_HTTP_FORBIDDEN = 403;
  // Même seuil que SuiviChroniciteService — un seul 403 peut être transitoire
  // (FCM), une répétition sur plusieurs cycles consécutifs signe un abonnement
  // définitivement invalide (VAPID/subscription).
  private static final int SEUIL_ECHECS_403_ABONNEMENT_MORT = 3;

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final NotificationDigestService.FabriquePushService fabriquePushService;
  // Bilan de panne push agrégé sur le cycle de digest courant, remonté dans
  // logs_admin.
  private final CompteurBilanFetch compteurPush = new CompteurBilanFetch();

  /* package-private : instancié uniquement par Spring et les tests du même
   * package. */
  NotificationPushDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final NotificationDigestService.FabriquePushService fabriquePushService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
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

  /**
   * Issue possible d'un envoi push individuel, selon le statut HTTP retourné
   * par le push service.
   */
  /* package-private : type interne, utilisé uniquement au sein de cette
   * classe/des tests du même package. */
  enum ResultatEnvoiPush {
    SUCCES,
    ABONNEMENT_MORT,
    ECHEC
  }

  /**
   * Interprète le statut HTTP d'un envoi push — seul 2xx est un succès,
   * 404/410 signent un abonnement mort, le reste (400/429/5xx) est un
   * échec (avant : tout code hors 404/410 comptait à tort en succès).
   * @param status statut HTTP retourné par le push service
   * @return issue de l'envoi
   */
  /* package-private : appelé par les tests du même package. */
  static ResultatEnvoiPush interpreterStatutPush(final int status) {
    if (status == 404 || status == 410) {
      return ResultatEnvoiPush.ABONNEMENT_MORT;
    }
    if (status >= 200 && status < 300) {
      return ResultatEnvoiPush.SUCCES;
    }
    return ResultatEnvoiPush.ECHEC;
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
        jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", ab.get("id")));
        LOG.warn("Abonnement push hors services de push supprimé : {}", userId);
        continue;
      }
      compteurPush.enregistrerAppel();
      try {
        final String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
        final String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
        final Subscription subscription =
            new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
        final Notification notification = new Notification(subscription, payload);
        final HttpResponse response = pushService.send(notification);
        final int status = response.getStatusLine().getStatusCode();
        switch (interpreterStatutPush(status)) {
          case ABONNEMENT_MORT -> {
            // Abonnement mort (410/404) : nettoyage attendu, pas une panne du
            // canal push — n'entre pas dans le bilan de panne.
            jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", ab.get("id")));
            LOG.info("Abonnement push révoqué : {} — {}", userId, ab.get(CLE_ENDPOINT));
          }
          case SUCCES -> {
            nbEnvoyes++;
            reinitialiserEchecs403(ab);
          }
          case ECHEC -> {
            // tout statut hors 2xx/404/410 (400, 429, 5xx...) est un échec, pas
            // un succès implicite — sans cette branche, un push jamais délivré
            // était compté comme réussi.
            compteurPush.enregistrerErreur();
            if (status == STATUT_HTTP_FORBIDDEN) {
              traiterEchec403(userId, ab);
            } else {
              LOG.warn(
                  "Push: statut inattendu {} pour {} — {}", status, userId, ab.get(CLE_ENDPOINT));
            }
          }
        }
      } catch (Exception e) {
        compteurPush.enregistrerErreur();
        LOG.warn("Push: échec envoi pour {} — {}", userId, ab.get(CLE_ENDPOINT), e);
      }
    }
    return nbEnvoyes;
  }

  private void reinitialiserEchecs403(final Map<String, Object> ab) {
    jdbc.update(
        "UPDATE push_abonnements SET echecs_403_consecutifs = 0 WHERE id = :id",
        Map.of("id", ab.get("id")));
  }

  /**
   * Un 403 isolé peut être transitoire (FCM) — seule une répétition sur
   * {@value #SEUIL_ECHECS_403_ABONNEMENT_MORT} cycles consécutifs révoque
   * l'abonnement, journalisée (jamais silencieuse, contrairement à 404/410).
   */
  private void traiterEchec403(final String userId, final Map<String, Object> ab) {
    final Integer echecs =
        jdbc.queryForObject(
            "UPDATE push_abonnements SET echecs_403_consecutifs = echecs_403_consecutifs + 1"
                + " WHERE id = :id RETURNING echecs_403_consecutifs",
            Map.of("id", ab.get("id")),
            Integer.class);
    if (echecs != null && echecs >= SEUIL_ECHECS_403_ABONNEMENT_MORT) {
      jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", ab.get("id")));
      LOG.info(
          "Abonnement push révoqué — 403 persistant sur {} cycles : {} — {}",
          echecs,
          userId,
          ab.get(CLE_ENDPOINT));
      logsAdminWorker.inserer(
          "warn",
          "notifier-digest",
          "abonnement push révoqué automatiquement — 403 persistant userId=" + userId,
          Map.of("userId", userId, "echecs403Consecutifs", echecs));
    } else {
      LOG.warn("Push: 403 ({}e consécutif) pour {} — {}", echecs, userId, ab.get(CLE_ENDPOINT));
    }
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
