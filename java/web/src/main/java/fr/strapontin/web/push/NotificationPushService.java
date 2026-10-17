/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.core.EchecPush;
import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.FournisseurBouncyCastle;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.security.GeneralSecurityException;
import java.util.HashMap;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Service de notifications Web Push VAPID — envoie des notifications aux
 * abonnements de {@code push_abonnements}, révocation des appareils en
 * échec selon {@link EchecPush}. Clés VAPID lues depuis la config chiffrée.
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class NotificationPushService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationPushService.class);
  private static final String CLE_ENDPOINT = "endpoint";
  private static final String CLE_USER_ID = "userId";
  private static final String CLE_ID = "id";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param configService lecture des clés VAPID
   * @param jdbc accès JDBC
   * @param logAdminService journalisation des envois en échec
   */
  public NotificationPushService(
      final ConfigService configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
    FournisseurBouncyCastle.garantir();
  }

  /**
   * Retourne la clé VAPID publique pour l'inscription côté navigateur.
   *
   * @return la clé VAPID publique encodée, ou vide si non configurée
   */
  public Optional<String> obtenirCleVapidPublique() {
    return configService.lireConfig("VAPID_PUBLIC_KEY");
  }

  private ResultatEnvoiPush envoyerAUnAbonnement(
      final Map<String, Object> ab,
      final PushService pushService,
      final String userId,
      final String payload) {
    if (!estAutoriseSinonSupprime(ab, userId)) {
      return ResultatEnvoiPush.SERVICE_NAVIGATEUR_REFUSE;
    }
    final HttpResponse response;
    try {
      final String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
      final String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
      final Subscription subscription =
          new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
      response = pushService.send(new Notification(subscription, payload), Encoding.AES128GCM);
    } catch (Exception e) {
      return compterEchec(ab, userId, null, e.getMessage());
    }
    final int status = response.getStatusLine().getStatusCode();
    return switch (EchecPush.interpreter(status)) {
      case SUCCES -> {
        jdbc.update(EchecPush.SQL_REINITIALISER_ECHECS, Map.of(CLE_ID, ab.get(CLE_ID)));
        yield ResultatEnvoiPush.ENVOYEE;
      }
      case ABONNEMENT_SUPPRIME -> {
        supprimerAbonnement(
            ab, userId, EchecPush.causeLisible(status), EchecPush.raison(status, null));
        yield ResultatEnvoiPush.APPAREIL_REVOQUE;
      }
      case ECHEC ->
          compterEchec(
              ab, userId, status, EchecPush.lireCorps(() -> response.getEntity().getContent()));
    };
  }

  /**
   * Un échec isolé peut être transitoire : seule une répétition sur
   * {@value EchecPush#SEUIL_ECHECS_CONSECUTIFS} envois consécutifs révoque
   * l'abonnement.
   */
  private ResultatEnvoiPush compterEchec(
      final Map<String, Object> ab, final String userId, final Integer status, final String corps) {
    final String raison = EchecPush.raison(status, corps);
    final Integer echecs =
        jdbc.queryForObject(
            EchecPush.SQL_INCREMENTER_ECHECS, Map.of(CLE_ID, ab.get(CLE_ID)), Integer.class);
    if (EchecPush.seuilAtteint(echecs)) {
      supprimerAbonnement(
          ab,
          userId,
          EchecPush.causeLisible(status),
          raison + ", " + echecs + " échecs consécutifs");
      return ResultatEnvoiPush.APPAREIL_REVOQUE;
    }
    LOG.warn(
        "Push: échec {} ({}e consécutif) pour {} — {}",
        raison,
        echecs,
        userId,
        EchecPush.masquerEndpoint((String) ab.get(CLE_ENDPOINT)));
    return ResultatEnvoiPush.ECHEC_ENVOI;
  }

  /**
   * Envoie une notification de test — cible l'abonnement de l'appareil
   * demandeur si {@code endpoint} est fourni plutôt que le plus récent
   * (sinon un utilisateur multi-appareils recevait le test sur un autre).
   * @param userId l'identifiant de l'utilisateur
   * @param endpoint l'endpoint de l'appareil demandeur, ou {@code null}
   * @return issue de l'envoi, avec la raison d'un échec
   */
  public ResultatEnvoiPush envoyerNotificationTest(final String userId, final String endpoint) {
    final Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    final Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      return ResultatEnvoiPush.SERVICE_NON_CONFIGURE;
    }

    final List<Map<String, Object>> abonnements =
        endpoint != null && !endpoint.isBlank()
            ? jdbc.queryForList(
                """
                SELECT id, endpoint, p256dh, auth
                FROM push_abonnements
                WHERE user_id = :userId AND endpoint = :endpoint
                """,
                Map.of(CLE_USER_ID, userId, CLE_ENDPOINT, endpoint))
            : jdbc.queryForList(
                """
                SELECT id, endpoint, p256dh, auth
                FROM push_abonnements
                WHERE user_id = :userId
                ORDER BY id DESC
                LIMIT 1
                """,
                Map.of(CLE_USER_ID, userId));
    if (abonnements.isEmpty()) {
      return ResultatEnvoiPush.APPAREIL_INCONNU;
    }
    if (!estAutoriseSinonSupprime(abonnements.get(0), userId)) {
      return ResultatEnvoiPush.SERVICE_NAVIGATEUR_REFUSE;
    }

    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String payload =
        """
        {"title":"Strapontin","body":"Notification de test","icon":"%s/images/icon-192.png"}\
        """
            .formatted(baseUrl);

    final PushService pushService;
    try {
      pushService = creerServicePush(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      signalerServiceInutilisable(e);
      return ResultatEnvoiPush.SERVICE_NON_CONFIGURE;
    }

    return envoyerAUnAbonnement(abonnements.get(0), pushService, userId, payload);
  }

  /**
   * Envoie une notification à tous les abonnements push (communication
   * d'incident) — à faire avant toute régénération des clés VAPID, qui les
   * invalide tous.
   * @param titre titre de la notification
   * @param message corps de la notification
   * @return nombre d'abonnements effectivement notifiés
   */
  public int envoyerATous(final String titre, final String message) {
    final Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    final Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      return 0;
    }
    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String payload;
    final PushService pushService;
    try {
      payload =
          JSON.writeValueAsString(
              Map.of(
                  "title",
                  titre,
                  "body",
                  message,
                  "url",
                  "/",
                  "icon",
                  baseUrl + "/images/icon-192.png"));
      pushService = creerServicePush(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      signalerServiceInutilisable(e);
      return 0;
    }
    int envoyes = 0;
    for (final Map<String, Object> ab :
        jdbc.queryForList(
            "SELECT id, user_id, endpoint, p256dh, auth FROM push_abonnements", Map.of())) {
      if (envoyerAUnAbonnement(ab, pushService, (String) ab.get("user_id"), payload)
          == ResultatEnvoiPush.ENVOYEE) {
        envoyes++;
      }
    }
    LOG.info("communication d'incident envoyée par push : {}", envoyes);
    return envoyes;
  }

  /**
   * @param clePublique clé VAPID publique
   * @param clePrivee clé VAPID privée
   * @param sujet sujet VAPID ({@code mailto:})
   * @return service d'envoi, redéfini en test pour intercepter le transport
   * @throws GeneralSecurityException si les clés sont illisibles
   */
  /* package-private : redéfini par les tests du même package. */
  PushService creerServicePush(final String clePublique, final String clePrivee, final String sujet)
      throws GeneralSecurityException {
    return new PushService(clePublique, clePrivee, sujet);
  }

  // Sans trace admin, une panne du service ne se voyait que dans le journal.
  private void signalerServiceInutilisable(final Exception erreur) {
    LOG.error("Impossible d'initialiser le service VAPID", erreur);
    logAdminService.logAdmin(
        "error",
        "web",
        "service push inutilisable : clés VAPID illisibles",
        Map.of("cause", erreur.getClass().getSimpleName()));
  }

  // Un abonnement enregistré avant la validation des endpoints n'est jamais appelé.
  private boolean estAutoriseSinonSupprime(final Map<String, Object> ab, final String userId) {
    if (EndpointPush.estAutorise((String) ab.get(CLE_ENDPOINT))) {
      return true;
    }
    supprimerAbonnement(
        ab, userId, EchecPush.CAUSE_SERVICE_NON_PRIS_EN_CHARGE, "endpoint non autorisé");
    return false;
  }

  private void supprimerAbonnement(
      final Map<String, Object> ab, final String userId, final String cause, final String raison) {
    jdbc.update(EchecPush.SQL_SUPPRIMER_ABONNEMENT, Map.of(CLE_ID, ab.get(CLE_ID)));
    jdbc.update(EchecPush.SQL_DATER_RETRAIT, Map.of(CLE_USER_ID, userId, "cause", cause));
    LOG.info("Abonnement push révoqué ({}) : {}", raison, userId);
    final Map<String, Object> meta = new HashMap<>();
    meta.put(CLE_USER_ID, userId);
    meta.put("raison", raison);
    meta.put("service", EchecPush.masquerEndpoint((String) ab.get(CLE_ENDPOINT)));
    logAdminService.logAdmin("warn", "web", "abonnement push révoqué (" + raison + ")", meta);
  }
}
