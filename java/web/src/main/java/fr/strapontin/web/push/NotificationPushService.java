/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.FournisseurBouncyCastle;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * abonnements de {@code push_abonnements} (supprime automatiquement un
 * abonnement en 404/410). Clés VAPID lues depuis la config chiffrée.
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class NotificationPushService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationPushService.class);
  private static final String CLE_ENDPOINT = "endpoint";
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
    try {
      final String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
      final String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
      final Subscription subscription =
          new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
      final Notification notification = new Notification(subscription, payload);
      final HttpResponse response = pushService.send(notification);
      final int status = response.getStatusLine().getStatusCode();
      if (status == 404 || status == 410) {
        supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
        return ResultatEnvoiPush.APPAREIL_REVOQUE;
      }
      if (status >= 400) {
        LOG.warn("Push: envoi refusé ({}) pour {} — {}", status, userId, ab.get(CLE_ENDPOINT));
        return ResultatEnvoiPush.ECHEC_ENVOI;
      }
      return ResultatEnvoiPush.ENVOYEE;
    } catch (Exception e) {
      final int status = extraireCodeHttp(e);
      if (status == 404 || status == 410) {
        supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
        return ResultatEnvoiPush.APPAREIL_REVOQUE;
      }
      LOG.warn("Push: échec envoi pour {} — {}", userId, ab.get(CLE_ENDPOINT), e);
      return ResultatEnvoiPush.ECHEC_ENVOI;
    }
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
                Map.of("userId", userId, CLE_ENDPOINT, endpoint))
            : jdbc.queryForList(
                """
                SELECT id, endpoint, p256dh, auth
                FROM push_abonnements
                WHERE user_id = :userId
                ORDER BY id DESC
                LIMIT 1
                """,
                Map.of("userId", userId));
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
   * Point de construction unique du service d'envoi, remplaçable en test pour
   * n'intercepter que le transport HTTP.
   * @param clePublique clé VAPID publique
   * @param clePrivee clé VAPID privée
   * @param sujet sujet VAPID ({@code mailto:})
   * @return service d'envoi prêt à chiffrer et signer
   * @throws GeneralSecurityException si les clés sont illisibles
   */
  /* package-private : redéfini par les tests du même package. */
  PushService creerServicePush(final String clePublique, final String clePrivee, final String sujet)
      throws GeneralSecurityException {
    return new PushService(clePublique, clePrivee, sujet);
  }

  // Sans trace admin, une panne du service ne se voyait que dans le journal.
  private void signalerServiceInutilisable(final Exception e) {
    LOG.error("Impossible d'initialiser le service VAPID", e);
    logAdminService.logAdmin(
        "error",
        "web",
        "service push inutilisable : clés VAPID illisibles",
        Map.of("cause", e.getClass().getSimpleName()));
  }

  // Un abonnement enregistré avant la validation des endpoints n'est jamais appelé.
  private boolean estAutoriseSinonSupprime(final Map<String, Object> ab, final String userId) {
    if (EndpointPush.estAutorise((String) ab.get(CLE_ENDPOINT))) {
      return true;
    }
    supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
    return false;
  }

  private void supprimerAbonnement(final UUID id, final String userId, final String endpoint) {
    jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", id));
    LOG.info("Abonnement push révoqué, supprimé : {} — {}", userId, endpoint);
    logAdminService.logAdmin("info", "web", "abonnement push révoqué", Map.of("userId", userId));
  }

  private static int extraireCodeHttp(final Exception exception) {
    final String msg = exception.getMessage();
    if (msg == null) {
      return -1;
    }
    // webpush-java peut lancer une exception avec le code HTTP dans le message
    try {
      final String[] parties = msg.split("\\s+");
      for (final String p : parties) {
        final int code = Integer.parseInt(p);
        if (code >= 400 && code < 600) {
          return code;
        }
      }
    } catch (NumberFormatException nfe) {
      LOG.trace("extraireCodeHttp : pas de code HTTP dans le message", nfe);
    }
    return -1;
  }
}
