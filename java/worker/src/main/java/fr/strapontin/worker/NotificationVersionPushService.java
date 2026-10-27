/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ClientWebPush;
import fr.strapontin.core.EchecPush;
import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.UtilitairesChaine;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Envoi push VAPID "nouvelle version disponible" — automatique chaque jour à
 * 11h (une seule annonce par version et par utilisateur) ou sur action admin,
 * distinct du digest de matching ({@link NotificationPushDigestService}),
 * avec la même règle de révocation des appareils en échec.
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class NotificationVersionPushService {

  private static final String CLE_ENDPOINT = "endpoint";
  private static final String SOURCE_LOGS = "push";
  private static final Logger LOG = LoggerFactory.getLogger(NotificationVersionPushService.class);

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final FabriquePushService fabriquePushService;
  private final SuiviEchecsPush suiviEchecs;

  /**
   * Constructeur Spring — {@code FabriquePushService} n'est jamais un bean
   * géré, la fabrique réelle ({@code ClientWebPush::new}) est fournie en dur
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
    this(configService, jdbc, logsAdminWorker, ClientWebPush::new);
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
      final FabriquePushService fabriquePushService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.fabriquePushService = fabriquePushService;
    this.suiviEchecs = new SuiviEchecsPush(jdbc, logsAdminWorker);
  }

  /**
   * Envoie la notification "nouvelle version" aux utilisateurs opt-in
   * ({@code notif_nouvelle_version}) ayant au moins un abonnement push actif et
   * qui n'ont pas déjà reçu cette version.
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

    final List<Map<String, Object>> abonnements = chargerAbonnementsOptIn(version);
    if (abonnements.isEmpty()) {
      return 0;
    }

    final String contactEmail = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final ClientWebPush pushService;
    try {
      pushService =
          fabriquePushService.creer(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      LOG.error("Impossible d'initialiser le service VAPID", e);
      return 0;
    }

    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    final String payload = construirePayload(version, baseUrl);
    final int nbEnvoyes = envoyerAAbonnements(pushService, abonnements, payload, version);

    logsAdminWorker.inserer(
        "info",
        "push",
        "notification nouvelle version envoyée",
        Map.of("version", version, "nbEnvoyes", nbEnvoyes, "nbAbonnements", abonnements.size()));
    return nbEnvoyes;
  }

  // Un utilisateur déjà notifié de cette version, ou dont la fréquence est «
  // Jamais », est écarté.
  private List<Map<String, Object>> chargerAbonnementsOptIn(final String version) {
    return jdbc.queryForList(
        """
        SELECT pa.id, pa.user_id, pa.endpoint, pa.p256dh, pa.auth
        FROM push_abonnements pa
        JOIN user_config uc ON uc.user_id = pa.user_id
        WHERE uc.notif_nouvelle_version = true
          AND uc.frequence_notifications <> 'jamais'
          AND NOT EXISTS (SELECT 1 FROM notifications_version nv
                          WHERE nv.user_id = pa.user_id AND nv.version = :version)
        """,
        Map.of("version", version));
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
      final ClientWebPush pushService,
      final List<Map<String, Object>> abonnements,
      final String payload,
      final String version) {
    int nbEnvoyes = 0;
    final Set<String> notifies = new LinkedHashSet<>();
    for (final Map<String, Object> ab : abonnements) {
      if (envoyerAUnAbonnement(pushService, ab, payload)) {
        nbEnvoyes++;
        notifies.add((String) ab.get("user_id"));
      }
    }
    // Un seul appareil atteint suffit : l'utilisateur est prévenu, pas de seconde
    // annonce.
    for (final String userId : notifies) {
      jdbc.update(
          "INSERT INTO notifications_version (user_id, version) VALUES (:userId, :version)"
              + " ON CONFLICT DO NOTHING",
          Map.of("userId", userId, "version", version));
    }
    return nbEnvoyes;
  }

  private boolean envoyerAUnAbonnement(
      final ClientWebPush pushService, final Map<String, Object> ab, final String payload) {
    final String userId = (String) ab.get("user_id");
    if (!EndpointPush.estAutorise((String) ab.get(CLE_ENDPOINT))) {
      suiviEchecs.revoquer(
          ab,
          userId,
          EchecPush.CAUSE_SERVICE_NON_PRIS_EN_CHARGE,
          "endpoint non autorisé",
          SOURCE_LOGS);
      return false;
    }
    final ClientWebPush.Reponse reponse;
    try {
      reponse =
          pushService.envoyer(
              (String) ab.get(CLE_ENDPOINT),
              (String) ab.get("p256dh"),
              (String) ab.get("auth"),
              payload);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      suiviEchecs.echec(ab, userId, null, e.getMessage(), SOURCE_LOGS);
      return false;
    }
    final int status = reponse.statut();
    switch (EchecPush.interpreter(status)) {
      case SUCCES -> {
        suiviEchecs.succes(ab);
        return true;
      }
      case ABONNEMENT_SUPPRIME ->
          suiviEchecs.revoquer(
              ab,
              userId,
              EchecPush.causeLisible(status),
              EchecPush.raison(status, null),
              SOURCE_LOGS);
      case ECHEC -> suiviEchecs.echec(ab, userId, status, reponse.corps(), SOURCE_LOGS);
    }
    return false;
  }
}
