/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.martijndwars.webpush.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de notification digest (email + push VAPID) pour le worker —
 * lit les {@code user_matches} non notifiés, groupe par utilisateur,
 * envoie et met à jour {@code notifie_le}. Canal ignoré sans erreur si
 * clé/quota absent ({@link GestionQuotaResendWorker} pour Resend).
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class NotificationDigestService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationDigestService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final NotificationPushDigestService pushDigestService;
  private final NotificationEmailDigestService emailDigestService;
  private final MatchesNonNotifiesLoader matchesLoader;
  private final NotificationDigestStats stats;

  /**
   * Fabrique du client Resend — indirection nécessaire pour rendre
   * {@code envoyerEmail} testable sur son chemin nominal de succès, le SDK
   * Resend n'étant pas injectable directement.
   */
  /* package-private : fabrique interne substituable en test, utilisée
   * uniquement par cette classe. */
  @FunctionalInterface
  interface FabriqueResend {
    /**
     * @param cle clé API Resend
     * @return client Resend prêt à l'emploi
     */
    Resend creer(String cle);
  }

  /** Fabrique du client push VAPID — même motif que {@link FabriqueResend}. */
  /* package-private : fabrique interne substituable en test, utilisée aussi par
   * NotificationPushDigestService (même package). */
  @FunctionalInterface
  interface FabriquePushService {
    /**
     * @param clePublique clé publique VAPID
     * @param clePrivee clé privée VAPID
     * @param sujet identifiant {@code mailto:} /{@code https:} requis par la
     *     spec VAPID
     * @return client push prêt à l'emploi
     * @throws java.security.GeneralSecurityException si les clés sont invalides
     */
    PushService creer(String clePublique, String clePrivee, String sujet)
        throws java.security.GeneralSecurityException;
  }

  /**
   * Constructeur Spring — : les fabriques Resend/PushService ne sont
   * explicitement annotées {@code @Autowired} que sur ce constructeur, sans
   * quoi Spring ne peut pas déterminer lequel des deux constructeurs utiliser
   * dès qu'il en existe plus d'un.
   */
  @Autowired
  public NotificationDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper jackson,
      final LogsAdminWorker logsAdminWorker,
      final GestionQuotaResendWorker gestionQuotaResendWorker) {
    this(
        configService,
        jdbc,
        jackson,
        logsAdminWorker,
        gestionQuotaResendWorker,
        Resend::new,
        PushService::new);
  }

  /**
   * Constructeur de test — permet d'injecter des fabriques Resend/PushService
   * mockables pour exercer le chemin nominal de succès de l'envoi email/push.
   */
  /* package-private : instancié uniquement par Spring et les tests du même
   * package. */
  NotificationDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper jackson,
      final LogsAdminWorker logsAdminWorker,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final FabriqueResend fabriqueResend,
      final FabriquePushService fabriquePushService) {
    this.jdbc = jdbc;
    this.pushDigestService =
        new NotificationPushDigestService(
            configService, jdbc, logsAdminWorker, fabriquePushService);
    this.emailDigestService =
        new NotificationEmailDigestService(configService, gestionQuotaResendWorker, fabriqueResend);
    this.matchesLoader = new MatchesNonNotifiesLoader(jdbc);
    this.stats = new NotificationDigestStats(jdbc, jackson);
  }

  /** Données d'un concert pour le digest. */
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

  /**
   * Résultat d'un envoi de digest, avec le sous-ensemble de concerts
   * effectivement tenté par canal — nécessaire pour ne marquer
   * {@code notification_envoyee_le} que sur les concerts réellement délivrés
   * par un canal en succès, pas sur l'ensemble du digest dès qu'un seul canal a
   * réussi.
   */
  private record ResultatDigest(
      boolean emailOk,
      boolean pushOk,
      List<ConcertNotif> concertsEmail,
      List<ConcertNotif> concertsPush) {}

  /* package-private : type de retour interne, utilisé par
   * NotificationPushDigestService (même package). */
  record ConfigNotifUser(
      boolean notifArtistes,
      boolean notifSalles,
      boolean notifArtistesPush,
      boolean notifSallesPush,
      String frequenceNotifications) {}

  /**
   * Parcourt les matches non notifiés pour une fréquence donnée, envoie le
   * digest, met à jour {@code notifie_le} .
   *
   * @param frequence {@code "temps_reel"} (après chaque recalcul) ou
   *     {@code "quotidien"} (cron 8h)
   */
  public void executerDigest(final String frequence) {
    LOG.info("notifier:digest démarré frequence={}", frequence);
    final long debut = System.currentTimeMillis();
    int nbUtilisateurs = 0;
    int nbMatchesNotifies = 0;
    int nbEmailsOk = 0;
    int nbPushOk = 0;
    int nbErreurs = 0;
    String erreur = null;

    try {
      final MatchesNonNotifiesLoader.MatchesGroupes groupes = matchesLoader.charger(frequence);

      if (groupes.parUtilisateur().isEmpty()) {
        LOG.info("notifier:digest — aucun match non notifié");
        return;
      }

      final Instant maintenant = Instant.now();
      for (final Map.Entry<String, List<ConcertNotif>> entree :
          groupes.parUtilisateur().entrySet()) {
        final BilanDigestUtilisateur bilan =
            traiterDigestUtilisateur(entree.getKey(), entree.getValue(), groupes, maintenant);
        nbEmailsOk += bilan.emailOk() ? 1 : 0;
        nbPushOk += bilan.pushOk() ? 1 : 0;
        nbErreurs += bilan.erreur() ? 1 : 0;
        nbMatchesNotifies += bilan.matchesNotifies();
        nbUtilisateurs++;
      }
    } catch (Exception e) {
      erreur = e.getMessage();
      LOG.error("notifier:digest échoué", e);
    } finally {
      enregistrerStat(System.currentTimeMillis() - debut, nbMatchesNotifies, erreur);
      logAdminDigest(nbEmailsOk, nbPushOk, nbErreurs);
      pushDigestService.remonterBilanPush();
      LOG.info("notifier:digest terminé users={} concerts={}", nbUtilisateurs, nbMatchesNotifies);
    }
  }

  /**
   * Bilan de l'envoi du digest à un utilisateur (extrait de
   * {@link #executerDigest} ).
   *
   * @param matchesNotifies nombre de matches marqués notifiés pour cet
   *     utilisateur
   */
  private record BilanDigestUtilisateur(
      boolean emailOk, boolean pushOk, boolean erreur, int matchesNotifies) {}

  private BilanDigestUtilisateur traiterDigestUtilisateur(
      final String userId,
      final List<ConcertNotif> concerts,
      final MatchesNonNotifiesLoader.MatchesGroupes groupes,
      final Instant maintenant) {
    final Set<UUID> concertIdsNotifies = new LinkedHashSet<>();
    boolean emailOk = false;
    boolean pushOk = false;
    boolean erreur = false;
    try {
      final ResultatDigest res =
          envoyerDigest(
              userId,
              groupes.emailsUtilisateurs().get(userId),
              concerts,
              groupes.configsUtilisateurs().get(userId));
      emailOk = res.emailOk();
      pushOk = res.pushOk();
      // Seuls les concerts du canal effectivement en succès sont marqués
      // notifiés : un digest mixte email+push où un seul canal échoue ne doit
      // pas marquer à tort les concerts du canal en échec comme notifiés.
      if (res.emailOk()) {
        res.concertsEmail().forEach(c -> concertIdsNotifies.add(UUID.fromString(c.concertId())));
      }
      if (res.pushOk()) {
        res.concertsPush().forEach(c -> concertIdsNotifies.add(UUID.fromString(c.concertId())));
      }
    } catch (Exception e) {
      erreur = true;
      LOG.error("notifier:digest erreur envoi pour user={} : {}", userId, e.getMessage());
    }
    final int matchesNotifies;
    if (concertIdsNotifies.isEmpty()) {
      LOG.warn(
          "notifier:digest — notification échouée pour user={}, notification_envoyee_le non mis"
              + " à jour",
          userId);
      matchesNotifies = 0;
    } else {
      matchesNotifies = marquerMatchesNotifies(userId, concertIdsNotifies, maintenant);
    }
    return new BilanDigestUtilisateur(emailOk, pushOk, erreur, matchesNotifies);
  }

  private int marquerMatchesNotifies(
      final String userId, final Set<UUID> concertIdsNotifies, final Instant maintenant) {
    return jdbc.update(
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
            // pgjdbc n'infère pas le type SQL d'un java.time.Instant nu en
            // paramètre de bind — OffsetDateTime est le seul type JSR-310
            // supporté automatiquement en écriture.
            .addValue("maintenant", maintenant.atOffset(java.time.ZoneOffset.UTC))
            .addValue("concertIds", concertIdsNotifies));
  }

  private ResultatDigest envoyerDigest(
      final String userId,
      final String email,
      final List<ConcertNotif> concerts,
      final ConfigNotifUser config) {
    final boolean emailActif = !"jamais".equals(config.frequenceNotifications());
    final List<ConcertNotif> concertsEmail =
        emailActif
            ? concerts.stream()
                .filter(
                    c ->
                        ("ARTISTE".equals(c.source()) && config.notifArtistes())
                            || ("SALLE".equals(c.source()) && config.notifSalles()))
                .toList()
            : List.of();
    final List<ConcertNotif> concertsPush =
        concerts.stream()
            .filter(
                c ->
                    ("ARTISTE".equals(c.source()) && config.notifArtistesPush())
                        || ("SALLE".equals(c.source()) && config.notifSallesPush()))
            .toList();
    final boolean emailOk = envoyerEmail(email, concertsEmail);
    final boolean pushOk = pushDigestService.envoyerPush(userId, concertsPush);
    return new ResultatDigest(emailOk, pushOk, concertsEmail, concertsPush);
  }

  private boolean envoyerEmail(final String email, final List<ConcertNotif> concerts) {
    return emailDigestService.envoyerEmail(email, concerts);
  }

  private void logAdminDigest(final int nbEmails, final int nbPush, final int nbErreurs) {
    stats.logAdminDigest(nbEmails, nbPush, nbErreurs);
  }

  private void enregistrerStat(final long dureeMs, final int nbNotifies, final String erreur) {
    stats.enregistrerStat(dureeMs, nbNotifies, erreur);
  }

  /* package-private : appelé par
   * NotificationPushDigestService/EmailDigestTemplate (même package). */
  static String formaterDate(final String dateIso) {
    return NotificationDigestStats.formaterDate(dateIso);
  }
}
