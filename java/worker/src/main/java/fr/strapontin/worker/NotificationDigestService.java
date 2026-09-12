/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Service de notification digest (email + push VAPID) pour le worker.
 *
 * <p>Lit les {@code user_matches} non notifiés, groupe par utilisateur, envoie email via Resend et
 * push VAPID, puis met à jour {@code notifie_le}.
 *
 * <p>Si {@code RESEND_API_KEY} ou les clés VAPID sont absentes de {@code app_config}, la
 * notification correspondante est ignorée sans erreur. Depuis, le canal email est de même ignoré
 * sans erreur si le quota d'envoi Resend jour/mois est atteint ({@link GestionQuotaResendWorker},
 * compteur partagé avec le module web).
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class NotificationDigestService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationDigestService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;
  private final GestionQuotaResendWorker gestionQuotaResendWorker;
  private final ConfigServiceWorker configService;
  private final FabriqueResend fabriqueResend;
  private final NotificationPushDigestService pushDigestService;
  private final MatchesNonNotifiesLoader matchesLoader;

  /**
   * Fabrique du client Resend — indirection nécessaire pour rendre {@code envoyerEmail} testable
   * sur son chemin nominal de succès, le SDK Resend n'étant pas injectable directement.
   */
  @FunctionalInterface
  interface FabriqueResend {
    Resend creer(String cle);
  }

  /** Fabrique du client push VAPID — même motif que {@link FabriqueResend}. */
  @FunctionalInterface
  interface FabriquePushService {
    PushService creer(String clePublique, String clePrivee, String sujet)
        throws java.security.GeneralSecurityException;
  }

  /**
   * Constructeur Spring — : les fabriques Resend/PushService ne sont explicitement annotées
   * {@code @Autowired} que sur ce constructeur, sans quoi Spring ne peut pas déterminer lequel des
   * deux constructeurs utiliser dès qu'il en existe plus d'un.
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
   * Constructeur de test — permet d'injecter des fabriques Resend/PushService mockables pour
   * exercer le chemin nominal de succès de l'envoi email/push.
   */
  NotificationDigestService(
      final ConfigServiceWorker configService,
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper jackson,
      final LogsAdminWorker logsAdminWorker,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final FabriqueResend fabriqueResend,
      final FabriquePushService fabriquePushService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.jackson = jackson;
    this.gestionQuotaResendWorker = gestionQuotaResendWorker;
    this.fabriqueResend = fabriqueResend;
    this.pushDigestService =
        new NotificationPushDigestService(
            configService, jdbc, logsAdminWorker, fabriquePushService);
    this.matchesLoader = new MatchesNonNotifiesLoader(jdbc);
  }

  /**
   * Données d'un concert pour le digest.
   *
   * @param artiste nom de l'artiste
   * @param date date ISO (yyyy-MM-dd)
   * @param salle nom de la salle
   * @param ville ville (peut être nulle)
   * @param url URL de billetterie (peut être nulle)
   * @param concertId UUID du concert (string)
   * @param artisteNorm nom de l'artiste normalisé (identique à {@code artiste_norm} en base)
   * @param source origine du match : {@code "ARTISTE"} ou {@code "SALLE"}
   */
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
   * Résultat d'un envoi de digest, avec le sous-ensemble de concerts effectivement tenté par canal
   * — nécessaire pour ne marquer {@code notification_envoyee_le} que sur les concerts réellement
   * délivrés par un canal en succès, pas sur l'ensemble du digest dès qu'un seul canal a réussi.
   */
  private record ResultatDigest(
      boolean emailOk,
      boolean pushOk,
      List<ConcertNotif> concertsEmail,
      List<ConcertNotif> concertsPush) {}

  record ConfigNotifUser(
      boolean notifArtistes,
      boolean notifSalles,
      boolean notifArtistesPush,
      boolean notifSallesPush,
      String frequenceNotifications) {}

  /**
   * Parcourt les matches non notifiés pour une fréquence donnée, envoie le digest, met à jour
   * {@code notifie_le}.
   *
   * @param frequence {@code "temps_reel"} (après chaque recalcul) ou {@code "quotidien"} (cron 8h)
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

      final Timestamp maintenant = Timestamp.from(Instant.now());
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
   * Bilan de l'envoi du digest à un utilisateur (extrait de {@link #executerDigest}).
   *
   * @param matchesNotifies nombre de matches marqués notifiés pour cet utilisateur
   */
  private record BilanDigestUtilisateur(
      boolean emailOk, boolean pushOk, boolean erreur, int matchesNotifies) {}

  private BilanDigestUtilisateur traiterDigestUtilisateur(
      final String userId,
      final List<ConcertNotif> concerts,
      final MatchesNonNotifiesLoader.MatchesGroupes groupes,
      final Timestamp maintenant) {
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
      // Seuls les concerts du canal effectivement en succès sont marqués notifiés :
      // un digest mixte email+push où un seul canal échoue ne doit pas marquer à tort les
      // concerts du canal en échec comme notifiés.
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
    if (!concertIdsNotifies.isEmpty()) {
      matchesNotifies = marquerMatchesNotifies(userId, concertIdsNotifies, maintenant);
    } else {
      LOG.warn(
          "notifier:digest — notification échouée pour user={}, notification_envoyee_le non mis"
              + " à jour",
          userId);
      matchesNotifies = 0;
    }
    return new BilanDigestUtilisateur(emailOk, pushOk, erreur, matchesNotifies);
  }

  private int marquerMatchesNotifies(
      final String userId, final Set<UUID> concertIdsNotifies, final Timestamp maintenant) {
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
            .addValue("maintenant", maintenant)
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
    if (concerts.isEmpty()) {
      return false;
    }
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      LOG.debug("RESEND_API_KEY absent — digest email ignoré");
      return false;
    }
    if (!gestionQuotaResendWorker.reserverEnvoi()) {
      // notification_envoyee_le n'est pas marquée pour ce canal (comportement déjà
      // existant, cf. clé "RESEND_API_KEY absent" ci-dessus) — retenté au prochain cycle de
      // digest plutôt que perdu.
      return false;
    }
    final String expediteur = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");

    final int nb = concerts.size();
    final String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);
    try {
      final Resend resend = fabriqueResend.creer(cle.get());
      final CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(expediteur)
              .replyTo(expediteur)
              .to(List.of(email))
              .subject(sujet)
              .html(EmailDigestTemplate.construireCorpsEmail(sujet, concerts, baseUrl))
              .text(sujet)
              .build();
      resend.emails().send(options);
      LOG.info("Digest email envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi digest email : {}", email, e);
      return false;
    }
  }

  private void logAdminDigest(int nbEmails, int nbPush, int nbErreurs) {
    try {
      final String metaJson =
          jackson.writeValueAsString(
              Map.of("nb_emails", nbEmails, "nb_push", nbPush, "nb_erreurs", nbErreurs));
      final String message = "digest — " + nbEmails + " email(s), " + nbPush + " push envoyés";
      final String niveau = nbErreurs > 0 ? "warn" : "info";
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          new MapSqlParameterSource()
              .addValue("ts", Instant.now().toEpochMilli())
              .addValue("niveau", niveau)
              .addValue("source", "worker")
              .addValue("message", message)
              .addValue("meta", metaJson));
    } catch (Exception e) {
      LOG.warn("Impossible d'écrire log admin digest : {}", e.getMessage());
    }
  }

  private void enregistrerStat(long dureeMs, int nbNotifies, String erreur) {
    try {
      final Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", "notifier:digest");
      params.put("now", Timestamp.from(Instant.now()));
      params.put("dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE));
      params.put("nbNotifies", nbNotifies);
      params.put("erreur", erreur);
      jdbc.update(
          """
          INSERT INTO fetch_jobs (type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, 0, :nbNotifies, :erreur)
          ON CONFLICT (type_job) DO UPDATE SET
            derniere_exec  = EXCLUDED.derniere_exec,
            duree_ms       = EXCLUDED.duree_ms,
            nb_inseres     = EXCLUDED.nb_inseres,
            nb_mis_a_jour  = EXCLUDED.nb_mis_a_jour,
            erreur         = EXCLUDED.erreur
          """,
          params);
    } catch (Exception e) {
      LOG.warn("Impossible d'enregistrer stat notifier:digest : {}", e.getMessage());
    }
  }

  static String formaterDate(final String dateIso) {
    final LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
