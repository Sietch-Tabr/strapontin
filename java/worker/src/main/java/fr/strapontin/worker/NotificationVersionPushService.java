/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.UtilitairesChaine;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Envoi push VAPID "nouvelle version disponible" — action admin manuelle,
 * distincte du digest de matching ({@link NotificationPushDigestService}) :
 * pas de nettoyage des abonnements morts ici, laissé au prochain cycle de
 * digest régulier.
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class NotificationVersionPushService {

  private static final String CLE_ENDPOINT = "endpoint";
  private static final Logger LOG = LoggerFactory.getLogger(NotificationVersionPushService.class);

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final NotificationDigestService.FabriquePushService fabriquePushService;

  /**
   * Constructeur Spring — {@code FabriquePushService} n'est jamais un bean
   * géré, la fabrique réelle ({@code PushService::new}) est fournie en dur
   * ici, même pattern que {@link NotificationDigestService}.
   * @param configService lecture des clés VAPID/PUBLIC_BASE_URL
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation du bilan d'envoi
   */
  @Autowired
  public NotificationVersionPushService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker) {
    this(configService, jdbc, logsAdminWorker, PushService::new);
  }

  /**
   * Constructeur de test — permet d'injecter une fabrique push mockable.
   * @param configService lecture des clés VAPID/PUBLIC_BASE_URL
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation du bilan d'envoi
   * @param fabriquePushService fabrique du client push VAPID
   */
  /* package-private : instancié uniquement par Spring et les tests du même
   * package. */
  NotificationVersionPushService(
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
   * Envoie la notification "nouvelle version" à tous les utilisateurs
   * opt-in ({@code notif_nouvelle_version}) ayant au moins un abonnement
   * push actif.
   * @param version version applicative à annoncer (ex. {@code "3.0.0"})
   * @return le nombre d'abonnements notifiés avec succès
   */
  public int notifierVersion(final String version) {
    final Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    final Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      LOG.debug("VAPID non configuré — notification nouvelle version ignorée");
      return 0;
    }

    final List<Map<String, Object>> abonnements = chargerAbonnementsOptIn();
    if (abonnements.isEmpty()) {
      return 0;
    }

    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final PushService pushService;
    try {
      pushService =
          fabriquePushService.creer(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      LOG.error("Impossible d'initialiser le service VAPID", e);
      return 0;
    }

    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String payload = construirePayload(version, baseUrl);
    final int nbEnvoyes = envoyerAAbonnements(pushService, abonnements, payload);

    logsAdminWorker.inserer(
        "info",
        "push",
        "notification nouvelle version envoyée",
        Map.of("version", version, "nbEnvoyes", nbEnvoyes, "nbAbonnements", abonnements.size()));
    return nbEnvoyes;
  }

  private List<Map<String, Object>> chargerAbonnementsOptIn() {
    return jdbc.queryForList(
        """
        SELECT pa.endpoint, pa.p256dh, pa.auth
        FROM push_abonnements pa
        JOIN user_config uc ON uc.user_id = pa.user_id
        WHERE uc.notif_nouvelle_version = true
        """,
        Map.of());
  }

  private static String construirePayload(final String version, final String baseUrl) {
    return """
    {"title":"Nouvelle version disponible","body":"%s","icon":"%s/images/icon-192.png","url":"/changelog"}\
    """
        .formatted(
            UtilitairesChaine.echapperJson("v" + version + " — Découvrez les nouveautés."),
            baseUrl);
  }

  private int envoyerAAbonnements(
      final PushService pushService,
      final List<Map<String, Object>> abonnements,
      final String payload) {
    int nbEnvoyes = 0;
    for (final Map<String, Object> ab : abonnements) {
      if (envoyerAUnAbonnement(pushService, ab, payload)) {
        nbEnvoyes++;
      }
    }
    return nbEnvoyes;
  }

  private boolean envoyerAUnAbonnement(
      final PushService pushService, final Map<String, Object> ab, final String payload) {
    if (!EndpointPush.estAutorise((String) ab.get(CLE_ENDPOINT))) {
      LOG.warn("Notification nouvelle version : endpoint hors services de push ignoré");
      return false;
    }
    try {
      final String p256dh = ((String) ab.get("p256dh")).replace("+", "-").replace("/", "_");
      final String auth = ((String) ab.get("auth")).replace("+", "-").replace("/", "_");
      final Subscription subscription =
          new Subscription((String) ab.get(CLE_ENDPOINT), new Subscription.Keys(p256dh, auth));
      final Notification notification = new Notification(subscription, payload);
      final HttpResponse response = pushService.send(notification);
      final int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        return true;
      }
      LOG.warn(
          "Notification nouvelle version : statut inattendu {} — {}", status, ab.get(CLE_ENDPOINT));
      return false;
    } catch (Exception e) {
      LOG.warn("Notification nouvelle version : échec envoi — {}", ab.get(CLE_ENDPOINT), e);
      return false;
    }
  }
}
