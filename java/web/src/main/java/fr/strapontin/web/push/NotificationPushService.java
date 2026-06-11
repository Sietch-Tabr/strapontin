/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.core.Normalisation;
import fr.strapontin.core.UtilitairesChaine;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/// Service de notifications Web Push VAPID (portage de {@code push.ts}).
///
/// <p>Envoie des notifications aux abonnements enregistrés dans {@code push_abonnements}.
/// Sur réponse 404 ou 410 de l'endpoint push, l'abonnement est automatiquement supprimé.
///
/// <p>Les clés VAPID sont lues depuis la configuration chiffrée (Caffeine 5 min).
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class NotificationPushService {

  private static final Logger log = LoggerFactory.getLogger(NotificationPushService.class);
  private static final String CLE_ENDPOINT = "endpoint";

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public NotificationPushService(
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Paramètres d'un concert pour la notification push.
  ///
  /// @param artiste le nom de l'artiste
  /// @param date    la date formatée (affichage direct)
  /// @param salle   le nom de la salle
  /// @param ville   la ville (peut être nulle)
  /// @param url     l'URL de billetterie (peut être nulle)
  public record ConcertPush(String artiste, String date, String salle, String ville, String url) {}

  /// Retourne la clé VAPID publique pour l'inscription côté navigateur.
  ///
  /// @return la clé VAPID publique encodée, ou vide si non configurée
  public Optional<String> obtenirCleVapidPublique() {
    return configService.lireConfig("VAPID_PUBLIC_KEY");
  }

  /// Envoie un digest push à tous les abonnements d'un utilisateur.
  ///
  /// @param userId   l'identifiant de l'utilisateur
  /// @param concerts la liste des concerts à notifier
  /// @return {@code true} si au moins une notification a été envoyée
  public boolean envoyerPushDigest(String userId, List<ConcertPush> concerts) {
    if (concerts.isEmpty()) return false;

    Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      log.warn("VAPID non configuré — push ignoré pour {}", userId);
      return false;
    }

    List<Map<String, Object>> abonnements =
        jdbc.queryForList(
            """
            SELECT id, endpoint, p256dh, auth
            FROM push_abonnements
            WHERE user_id = :userId
            """,
            Map.of("userId", userId));
    if (abonnements.isEmpty()) return false;

    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    String payload = construirePayload(concerts, baseUrl);

    PushService pushService;
    try {
      pushService = new PushService(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      log.error("Impossible d'initialiser le service VAPID", e);
      return false;
    }

    int nbEnvoyes = 0;
    for (Map<String, Object> ab : abonnements) {
      nbEnvoyes += envoyerAUnAbonnement(ab, pushService, userId, payload);
    }

    if (nbEnvoyes > 0) {
      log.info("Push digest envoyé : {} ({} abonnement(s))", userId, nbEnvoyes);
    }
    return nbEnvoyes > 0;
  }

  private int envoyerAUnAbonnement(
      Map<String, Object> ab, PushService pushService, String userId, String payload) {
    try {
      String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
      String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
      Subscription subscription =
          new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
      Notification notification = new Notification(subscription, payload);
      HttpResponse response = pushService.send(notification);
      int status = response.getStatusLine().getStatusCode();
      if (status == 404 || status == 410) {
        supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
        return 0;
      }
      return 1;
    } catch (Exception e) {
      int status = extraireCodeHttp(e);
      if (status == 404 || status == 410) {
        supprimerAbonnement((UUID) ab.get("id"), userId, (String) ab.get(CLE_ENDPOINT));
      } else {
        log.warn("Push: échec envoi pour {} — {}", userId, ab.get(CLE_ENDPOINT), e);
      }
      return 0;
    }
  }

  /// Envoie une notification de test au dernier abonnement de l'utilisateur.
  ///
  /// @param userId l'identifiant de l'utilisateur
  /// @return {@code true} si la notification a été envoyée
  public boolean envoyerNotificationTest(String userId) {
    Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) return false;

    List<Map<String, Object>> abonnements =
        jdbc.queryForList(
            """
            SELECT id, endpoint, p256dh, auth
            FROM push_abonnements
            WHERE user_id = :userId
            ORDER BY id DESC
            LIMIT 1
            """,
            Map.of("userId", userId));
    if (abonnements.isEmpty()) return false;

    String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    String payload =
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

  private void supprimerAbonnement(UUID id, String userId, String endpoint) {
    jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", id));
    log.info("Abonnement push révoqué, supprimé : {} — {}", userId, endpoint);
    logAdminService.logAdmin("info", "web", "abonnement push révoqué", Map.of("userId", userId));
  }

  private static String construirePayload(List<ConcertPush> concerts, String baseUrl) {
    ConcertPush premier = concerts.get(0);
    String titre =
        concerts.size() == 1
            ? premier.artiste() + " — " + premier.salle()
            : concerts.size() + " nouveaux concerts";

    String corps =
        concerts.size() == 1
            ? premier.date() + (premier.ville() != null ? " · " + premier.ville() : "")
            : concerts.stream()
                .map(c -> c.artiste() + " · " + c.date())
                .collect(java.util.stream.Collectors.joining("\n"));

    String url =
        concerts.size() == 1
            ? baseUrl
                + "/?ancre=artiste-"
                + URLEncoder.encode(
                    Normalisation.normaliserTexte(premier.artiste()), StandardCharsets.UTF_8)
            : baseUrl + "/?nouveau=true";

    // JSON construit manuellement (pas de dépendance Jackson pour ce payload simple)
    return """
    {"title":"%s","body":"%s","icon":"%s/images/icon-192.png","url":"%s"}\
    """
        .formatted(
            UtilitairesChaine.echapperJson(titre),
            UtilitairesChaine.echapperJson(corps),
            baseUrl,
            UtilitairesChaine.echapperJson(url));
  }

  private static int extraireCodeHttp(Exception e) {
    String msg = e.getMessage();
    if (msg == null) return -1;
    // webpush-java peut lancer une exception avec le code HTTP dans le message
    try {
      String[] parties = msg.split("\\s+");
      for (String p : parties) {
        int code = Integer.parseInt(p);
        if (code >= 400 && code < 600) return code;
      }
    } catch (NumberFormatException _) {
      // pas de code HTTP dans le message
    }
    return -1;
  }
}
