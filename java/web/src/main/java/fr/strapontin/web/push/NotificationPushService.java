/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
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

/**
 * Service de notifications Web Push VAPID (portage de {@code push.ts}).
 *
 * <p>Envoie des notifications aux abonnements enregistrés dans {@code push_abonnements}.
 * Sur réponse 404 ou 410 de l'endpoint push, l'abonnement est automatiquement supprimé.
 *
 * <p>Les clés VAPID sont lues depuis la configuration chiffrée (Caffeine 5 min).
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class NotificationPushService {

  private static final Logger log = LoggerFactory.getLogger(NotificationPushService.class);
  private static final String CLE_ENDPOINT = "endpoint";

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public NotificationPushService(
      final ConfigService configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Retourne la clé VAPID publique pour l'inscription côté navigateur.
   *
   * @return la clé VAPID publique encodée, ou vide si non configurée
   */
  public Optional<String> obtenirCleVapidPublique() {
    return configService.lireConfig("VAPID_PUBLIC_KEY");
  }

  private int envoyerAUnAbonnement(
      Map<String, Object> ab, PushService pushService, String userId, String payload) {
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
        return 0;
      }
      return 1;
    } catch (Exception e) {
      final int status = extraireCodeHttp(e);
      if (status == 404 || status == 410) {
        supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
      } else {
        log.warn("Push: échec envoi pour {} — {}", userId, ab.get(CLE_ENDPOINT), e);
      }
      return 0;
    }
  }

  /**
   * Envoie une notification de test à l'abonnement de l'utilisateur courant.
   *
   * <p>Si {@code endpoint} est fourni (l'appareil demandeur, disponible côté client via
   * {@code PushManager.getSubscription()}), cible précisément cet abonnement plutôt que de
   * sélectionner arbitrairement le plus récent par {@code id} — un utilisateur abonné sur
   * plusieurs appareils cliquant "Test" depuis l'un d'eux recevait auparavant la notification
   * sur un autre appareil, sans lien garanti avec la session courante (un UUID v4 n'a pas de
   * corrélation temporelle). Repli sur le plus récent si {@code endpoint} absent.
   *
   * @param userId   l'identifiant de l'utilisateur
   * @param endpoint l'endpoint de l'abonnement de l'appareil demandeur, ou {@code null}
   * @return {@code true} si la notification a été envoyée
   */
  public boolean envoyerNotificationTest(final String userId, final String endpoint) {
    final Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    final Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      return false;
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
      return false;
    }

    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String payload =
        """
            {"title":"Strapontin","body":"Notification de test","icon":"%s/images/icon-192.png"}\
            """
            .formatted(baseUrl);

    PushService pushService;
    try {
      pushService = new PushService(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      log.error("Impossible d'initialiser le service VAPID", e);
      return false;
    }

    return envoyerAUnAbonnement(abonnements.get(0), pushService, userId, payload) > 0;
  }

  private void supprimerAbonnement(final UUID id, final String userId, final String endpoint) {
    jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", id));
    log.info("Abonnement push révoqué, supprimé : {} — {}", userId, endpoint);
    logAdminService.logAdmin("info", "web", "abonnement push révoqué", Map.of("userId", userId));
  }

  private static int extraireCodeHttp(final Exception e) {
    final String msg = e.getMessage();
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
    } catch (NumberFormatException _) {
      // pas de code HTTP dans le message
    }
    return -1;
  }
}
