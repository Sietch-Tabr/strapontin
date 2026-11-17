/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import fr.strapontin.worker.ConfigServiceWorker;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

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

  private final NotificationPushDigestService pushDigestService;
  private final NotificationEmailDigestService emailDigestService;
  private final MatchesNonNotifiesLoader matchesLoader;
  private final NotificationDigestStats stats;

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
        FabriqueResend.parDefaut(),
        FabriquePushService.parDefaut());
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
    this(
        configService,
        jdbc,
        jackson,
        logsAdminWorker,
        gestionQuotaResendWorker,
        new Fabriques(fabriqueResend, fabriquePushService, Clock.systemUTC()));
  }

  /**
   * Fabriques d'envoi et horloge : la plage silencieuse du push dépend de
   * l'heure, un test ne doit pas en dépendre.
   */
  /* package-private : utilisé par les tests du même package. */
  record Fabriques(FabriqueResend resend, FabriquePushService push, Clock horloge) {}

  /**
   * Constructeur de test avec horloge.
   */
  /* package-private : instancié uniquement par les tests du même package. */
  NotificationDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper jackson,
      final LogsAdminWorker logsAdminWorker,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final Fabriques fabriques) {
    this.pushDigestService =
        new NotificationPushDigestService(
            configService, jdbc, logsAdminWorker, fabriques.push(), fabriques.horloge());
    this.emailDigestService =
        new NotificationEmailDigestService(
            configService, gestionQuotaResendWorker, fabriques.resend());
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
   * @param frequence {@code "temps_reel"} (après chaque recalcul),
   *     {@code "quotidien"} (cron 12h) ou {@code "hebdomadaire"} (dimanche 12h)
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

      for (final Map.Entry<String, List<ConcertNotif>> entree :
          groupes.parUtilisateur().entrySet()) {
        final BilanDigestUtilisateur bilan =
            traiterDigestUtilisateur(entree.getKey(), entree.getValue(), groupes);
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
   * Envoie à 7 h les push retenus pendant la plage silencieuse (22 h à 7 h,
   * heure de Paris).
   */
  public void envoyerPushDifferes() {
    final int utilisateurs = pushDigestService.envoyerDifferes();
    pushDigestService.remonterBilanPush();
    LOG.info("notifier:digest — push retenus envoyés, utilisateurs={}", utilisateurs);
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
      final MatchesNonNotifiesLoader.MatchesGroupes groupes) {
    List<String> concertIdsNotifies = List.of();
    boolean emailOk = false;
    boolean pushOk = false;
    boolean erreur = false;
    boolean sansCanal = false;
    try {
      final ResultatDigest res =
          envoyerDigest(
              userId,
              groupes.emailsUtilisateurs().get(userId),
              concerts,
              groupes.configsUtilisateurs().get(userId));
      emailOk = res.emailOk();
      pushOk = res.pushOk();
      sansCanal =
          res.sansCanal(
              pushDigestService.serviceDisponible() && pushDigestService.aUnAbonnement(userId),
              emailDigestService.serviceDisponible());
      // Seuls les concerts du canal effectivement en succès sont marqués
      // notifiés : un digest mixte email+push où un seul canal échoue ne doit
      // pas marquer à tort les concerts du canal en échec comme notifiés.
      concertIdsNotifies = res.concertIdsNotifies();
    } catch (Exception e) {
      erreur = true;
      LOG.error("notifier:digest erreur envoi pour user={} : {}", userId, e.getMessage());
    }
    final int matchesNotifies;
    if (concertIdsNotifies.isEmpty() && sansCanal) {
      LOG.info("notifier:digest — aucun canal joignable pour user={}", userId);
      matchesNotifies = 0;
    } else if (concertIdsNotifies.isEmpty()) {
      LOG.warn(
          "notifier:digest — notification échouée pour user={}, notification_envoyee_le non mis"
              + " à jour",
          userId);
      matchesNotifies = 0;
    } else {
      matchesNotifies = matchesLoader.marquerNotifies(userId, concertIdsNotifies);
    }
    return new BilanDigestUtilisateur(emailOk, pushOk, erreur, matchesNotifies);
  }

  private ResultatDigest envoyerDigest(
      final String userId,
      final String email,
      final List<ConcertNotif> concerts,
      final ConfigNotifUser config) {
    // « Jamais » coupe emails et push.
    final boolean notificationsActives = !"jamais".equals(config.frequenceNotifications());
    final List<ConcertNotif> concertsEmail =
        notificationsActives
            ? concerts.stream()
                .filter(
                    c ->
                        ("ARTISTE".equals(c.source()) && config.notifArtistes())
                            || ("SALLE".equals(c.source()) && config.notifSalles()))
                .toList()
            : List.of();
    final List<ConcertNotif> concertsPush =
        notificationsActives
            ? concerts.stream()
                .filter(
                    c ->
                        ("ARTISTE".equals(c.source()) && config.notifArtistesPush())
                            || ("SALLE".equals(c.source()) && config.notifSallesPush()))
                .toList()
            : List.of();
    final boolean emailOk = envoyerEmail(userId, email, concertsEmail);
    final boolean pushOk = pushDigestService.envoyerPush(userId, concertsPush);
    return new ResultatDigest(emailOk, pushOk, concertsEmail, concertsPush);
  }

  private boolean envoyerEmail(
      final String userId, final String email, final List<ConcertNotif> concerts) {
    return emailDigestService.envoyerEmail(userId, email, concerts);
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

  /**
   * Libellé court d'un statut à signaler dans un digest, ou une chaîne vide
   * quand le statut n'a rien de notable.
   * @param statut statut de vente du concert, peut être {@code null}
   * @return le libellé, ou une chaîne vide
   */
  /* package-private : appelé par NotificationPushDigestService (même package). */
  static String etiquetteStatut(final String statut) {
    return switch (statut == null ? "" : statut) {
      case "presale" -> "Prévente";
      case "rescheduled" -> "Date reportée";
      default -> "";
    };
  }
}
