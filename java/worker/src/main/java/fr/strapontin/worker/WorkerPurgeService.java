/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.GenerateurSecrets;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Jobs de purge (concerts, salles, artistes orphelins, logs admin) —
 * extrait de {@code WorkerService}. Délégué par les jobs Quartz {@code
 * PurgerConcertsJob}/{@code PurgerSallesJob}/{@code PurgerArtistesJob}/
 * {@code PurgerLogsAdminJob}.
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class WorkerPurgeService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerPurgeService.class);
  private static final String CLE_SEUIL = "seuil";

  // Seuil de purge définitive d'un concert futur jamais reconfirmé — très
  // supérieur au seuil de simple signalement "à vérifier"
  // (WorkerFetchCommun.SEUIL_ABSENCES_CONSECUTIVES, une demi- journée de
  // silence) : un état "à vérifier" posé par ce garde-fou anti-faux-positif ne
  // doit pas rester affiché indéfiniment sans jamais évoluer. ×10 correspond à
  // environ 5 jours de silence continu (cron ~11 cycles/jour), largement
  // au-delà de toute panne transitoire de source déjà observée.
  /* package-private : visibilité élargie pour WorkerPurgeServiceTest (même
   * package). */
  static final int SEUIL_PURGE_ABSENCES = WorkerFetchCommun.SEUIL_ABSENCES_CONSECUTIVES * 10;

  // Un concert sans salle_id ni artiste_id n'est jamais retouché par aucun job
  // de fetch (tous scopés par artiste suivi, salle suivie ou département suivi)
  // — absences_consecutives reste figé à 0 et ne peut donc jamais atteindre
  // SEUIL_PURGE_ABSENCES. 30 jours sans mise à jour signe une staleness réelle,
  // indépendante de ce compteur inopérant pour ce cas.
  private static final int SEUIL_JOURS_HORS_PORTEE = 30;

  // Purge de comptes : seuil d'inactivité, délai de grâce, et fenêtre de
  // relance/expiration d'une invitation jamais activée.
  private static final int SEUIL_MOIS_INACTIVITE = 6;
  private static final int DELAI_GRACE_JOURS = 15;
  private static final int SEUIL_JOURS_RELANCE_INVITATION = 6;
  private static final int SEUIL_JOURS_EXPIRATION_INVITATION = 7;
  // Au-delà, une seule exécution touche assez de comptes pour signer une anomalie de
  // référence (date de dernière activité manquante ou faussée) plutôt qu'une inactivité réelle.
  /* package-private : visibilité élargie pour WorkerPurgeServiceTest (même package). */
  static final int SEUIL_VAGUE_COMPTES = 3;
  private static final String CLE_ID = "id";
  private static final String CLE_EMAIL = "email";

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;
  private final PurgeCompteEmailService purgeCompteEmailService;
  private final ConfigServiceWorker configService;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param workerStatsService journalisation du bilan de purge
   * @param purgeCompteEmailService envoi des relances avant purge de compte
   * @param configService lecture de PUBLIC_BASE_URL (lien d'activation)
   * @param logsAdminWorker signalement admin des relances et suppressions de comptes
   */
  public WorkerPurgeService(
      final NamedParameterJdbcTemplate jdbc,
      final WorkerStatsService workerStatsService,
      final PurgeCompteEmailService purgeCompteEmailService,
      final ConfigServiceWorker configService,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
    this.purgeCompteEmailService = purgeCompteEmailService;
    this.configService = configService;
    this.logsAdminWorker = logsAdminWorker;
  }

  /** Supprime les concerts passés depuis plus de 7 jours. */
  public void executerPurgerConcerts() {
    final LocalDate dateLimite = LocalDate.now().minusDays(7);
    LOG.info("purger:concerts dateLimite={}", dateLimite);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts",
        () -> {
          final int supprimes =
              jdbc.update(
                  "DELETE FROM evenements WHERE date < :dateLimite",
                  Map.of("dateLimite", dateLimite));
          LOG.info("purger:concerts terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  /**
   * Signale (jamais supprime directement — {@code suppression_suggeree_le},
   * revue manuelle admin) les concerts futurs dont les absences consécutives
   * dépassent {@value #SEUIL_PURGE_ABSENCES} : un doublon de salle jamais
   * fusionné, ou un concert catalogué sous une autre attraction, peuvent
   * gonfler ce compteur pour un concert pourtant réel. Lève d'abord les flags
   * devenus caducs.
   */
  public void executerPurgerConcertsNonConfirmes() {
    LOG.info("purger:concerts-non-confirmes seuilAbsences={}", SEUIL_PURGE_ABSENCES);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts-non-confirmes",
        () -> {
          // Un flag dont aucune des deux conditions de pose ne tient plus
          // (concert reconfirmé, rattaché depuis, remis à zéro à la main) ne
          // doit pas s'accumuler dans la file de revue manuelle.
          final int leves =
              jdbc.update(
                  "UPDATE evenements SET suppression_suggeree_le = NULL"
                      + " WHERE suppression_suggeree_le IS NOT NULL"
                      + " AND absences_consecutives < :seuil"
                      + " AND NOT (salle_id IS NULL AND artiste_id IS NULL"
                      + " AND updated_at < now() - make_interval(days => :seuilJours))",
                  Map.of(CLE_SEUIL, SEUIL_PURGE_ABSENCES, "seuilJours", SEUIL_JOURS_HORS_PORTEE));
          final int signales =
              jdbc.update(
                  "UPDATE evenements SET suppression_suggeree_le = now()"
                      + " WHERE absences_consecutives >= :seuil AND date >= CURRENT_DATE"
                      + " AND suppression_suggeree_le IS NULL",
                  Map.of(CLE_SEUIL, SEUIL_PURGE_ABSENCES));
          LOG.info(
              "purger:concerts-non-confirmes terminé signales={} levesObsoletes={}",
              signales,
              leves);
          return new ResultatPersistance(0, signales);
        });
  }

  /**
   * Signale (même mécanisme non destructif que {@link
   * #executerPurgerConcertsNonConfirmes}) les concerts futurs sans {@code
   * salle_id} ni {@code artiste_id} — jamais réévalués par aucun fetch
   * (tous scopés par entité/département suivi), {@value
   * #SEUIL_JOURS_HORS_PORTEE} jours sans mise à jour sert de signal.
   */
  public void executerPurgerConcertsHorsPortee() {
    LOG.info("purger:concerts-hors-portee seuilJours={}", SEUIL_JOURS_HORS_PORTEE);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts-hors-portee",
        () -> {
          final int signales =
              jdbc.update(
                  "UPDATE evenements SET suppression_suggeree_le = now()"
                      + " WHERE salle_id IS NULL AND artiste_id IS NULL AND date >= CURRENT_DATE"
                      + " AND updated_at < now() - make_interval(days => :seuilJours)"
                      + " AND suppression_suggeree_le IS NULL",
                  Map.of("seuilJours", SEUIL_JOURS_HORS_PORTEE));
          LOG.info("purger:concerts-hors-portee terminé signales={}", signales);
          return new ResultatPersistance(0, signales);
        });
  }

  /**
   * Supprime les salles orphelines (sans concert futur ni utilisateur
   * suivi) — une par une, jamais en un seul {@code DELETE} en
   * masse : la cascade FK ({@code ON DELETE SET NULL}) peut entrer en
   * conflit avec {@code uq_evenements} sur un doublon TM/Eventim existant à
   * {@code salle_id = NULL}, ce qui bloquerait sinon toute la purge.
   * Après {@link #executerPurgerConcerts()}.
   */
  public void executerPurgerSalles() {
    LOG.info("purger:salles démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "purger:salles",
        () -> {
          final List<UUID> candidats =
              jdbc.query(
                  """
                  SELECT id FROM salles
                  WHERE NOT EXISTS (
                      SELECT 1 FROM concerts WHERE salle_id = salles.id AND date >= CURRENT_DATE)
                    AND NOT EXISTS (
                      SELECT 1 FROM user_salles WHERE salle_id = salles.id)
                  """,
                  Map.of(),
                  (rs, _) -> rs.getObject("id", UUID.class));

          int supprimes = 0;
          for (final UUID id : candidats) {
            try {
              jdbc.update("DELETE FROM salles WHERE id = CAST(:id AS uuid)", Map.of("id", id));
              supprimes++;
            } catch (DuplicateKeyException e) {
              LOG.warn(
                  "purger:salles suppression bloquée salleId={} — probable doublon TM/Eventim"
                      + " jamais fusionné",
                  id);
            }
          }

          LOG.info("purger:salles terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  /**
   * Supprime les artistes orphelins (sans concert futur ni utilisateur
   * suivi) — après {@link #executerPurgerSalles()}.
   */
  public void executerPurgerArtistes() {
    LOG.info("purger:artistes démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "purger:artistes",
        () -> {
          final int supprimes =
              jdbc.update(
                  """
                  DELETE FROM artistes
                  WHERE NOT EXISTS (
                      SELECT 1 FROM concerts WHERE artiste_id = artistes.id AND date >= CURRENT_DATE)
                    AND NOT EXISTS (
                      SELECT 1 FROM user_artistes WHERE artiste_id = artistes.id)
                  """,
                  Map.of());
          LOG.info("purger:artistes terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  /**
   * Supprime les entrées {@code logs_admin} > 30 jours, {@code
   * fetch_jobs_historique} > 7 jours, et les vérifications (tokens
   * d'invitation, emails de confirmation) expirées > 7 jours — rétention
   * temporelle et purge planifiée, remplace l'ancien plafond de 200
   * entrées purgé à chaque insertion.
   */
  public void executerPurgerLogsAdmin() {
    final Instant seuilLogs = Instant.now().minusSeconds(30L * 24 * 60 * 60);
    final Instant seuilHistorique = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    final Instant seuilVerifications = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    LOG.info("purger:logs-admin seuilLogs={} seuilHistorique={}", seuilLogs, seuilHistorique);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:logs-admin",
        () -> {
          final int supprimesLogs =
              jdbc.update(
                  "DELETE FROM logs_admin WHERE ts < :seuil",
                  Map.of(CLE_SEUIL, Timestamp.from(seuilLogs)));
          final int supprimesHistorique =
              jdbc.update(
                  "DELETE FROM fetch_jobs_historique WHERE exec_le < :seuil",
                  Map.of(CLE_SEUIL, Timestamp.from(seuilHistorique)));
          final int supprimesVerifications =
              jdbc.update(
                  "DELETE FROM verifications WHERE expires_at < :seuil",
                  Map.of(CLE_SEUIL, Timestamp.from(seuilVerifications)));
          LOG.info(
              "purger:logs-admin terminé supprimesLogs={} supprimesHistorique={}"
                  + " supprimesVerifications={}",
              supprimesLogs,
              supprimesHistorique,
              supprimesVerifications);
          return new ResultatPersistance(
              0, supprimesLogs + supprimesHistorique + supprimesVerifications);
        });
  }

  /**
   * Relance les comptes activés inactifs (email + délai de grâce, {@code
   * users.purge_le}), puis supprime ceux dont le délai est dépassé sans
   * activité — {@code derniere_connexion_le} est tenue à jour et {@code
   * purge_le} remis à {@code NULL} par le module web à chaque connexion et à
   * chaque journée d'activité, annulant la purge. Un compte sans date de
   * dernière activité n'est jamais relancé. Chaque relance ou suppression est
   * signalée dans {@code logs_admin}, en {@code warn} au-delà de {@link
   * #SEUIL_VAGUE_COMPTES}.
   */
  public void executerGererComptesInactifs() {
    LOG.info("gerer:comptes-inactifs démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "gerer:comptes-inactifs",
        () -> {
          final List<Map<String, Object>> aRelancer =
              jdbc.queryForList(
                  "SELECT id, email FROM users WHERE purge_le IS NULL"
                      + " AND must_change_credentials = false"
                      + " AND derniere_connexion_le < now() - make_interval(months => :seuilMois)",
                  Map.of("seuilMois", SEUIL_MOIS_INACTIVITE));
          int relances = 0;
          for (final Map<String, Object> ligne : aRelancer) {
            final String email = (String) ligne.get(CLE_EMAIL);
            if (purgeCompteEmailService.envoyerRelanceInactivite(email, DELAI_GRACE_JOURS)) {
              relances++;
            }
            jdbc.update(
                "UPDATE users SET purge_le = now() + make_interval(days => :delai)"
                    + " WHERE id = :id",
                Map.of("delai", DELAI_GRACE_JOURS, CLE_ID, ligne.get(CLE_ID)));
          }
          final int supprimes =
              jdbc.update(
                  "DELETE FROM users WHERE purge_le IS NOT NULL AND purge_le < now()", Map.of());
          LOG.info("gerer:comptes-inactifs terminé relances={} supprimes={}", relances, supprimes);
          signalerComptesTouches(aRelancer.size(), supprimes);
          return new ResultatPersistance(0, relances + supprimes);
        });
  }

  /**
   * Relance (nouvelle invitation/nouveau token) les comptes invités jamais
   * activés proches de l'expiration, puis supprime ceux dont l'invitation
   * d'origine a expiré sans activation.
   */
  public void executerGererInvitesExpires() {
    LOG.info("gerer:invites-expires démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "gerer:invites-expires",
        () -> {
          final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
          final List<Map<String, Object>> aRelancer =
              jdbc.queryForList(
                  "SELECT id, email FROM users WHERE must_change_credentials = true"
                      + " AND relance_invitation_envoyee_le IS NULL"
                      + " AND created_at < now() - make_interval(days => :seuil)",
                  Map.of(CLE_SEUIL, SEUIL_JOURS_RELANCE_INVITATION));
          int relances = 0;
          for (final Map<String, Object> ligne : aRelancer) {
            final String email = (String) ligne.get(CLE_EMAIL);
            final String token = GenerateurSecrets.genererToken(32);
            jdbc.update(
                "INSERT INTO verifications (id, identifier, value, expires_at, created_at,"
                    + " updated_at) VALUES (:vid, :identifier, :value,"
                    + " now() + interval '7 days', now(), now())",
                Map.of(
                    "vid", UUID.randomUUID().toString(),
                    "identifier", "invite:" + email,
                    "value", GenerateurSecrets.sha256Hex(token)));
            final String lien = baseUrl + "/reset-password?token=" + token;
            if (purgeCompteEmailService.envoyerRelanceInvitation(email, lien)) {
              relances++;
            }
            jdbc.update(
                "UPDATE users SET relance_invitation_envoyee_le = now() WHERE id = :id",
                Map.of(CLE_ID, ligne.get(CLE_ID)));
          }
          final int supprimes =
              jdbc.update(
                  "DELETE FROM users WHERE must_change_credentials = true"
                      + " AND created_at < now() - make_interval(days => :seuil)",
                  Map.of(CLE_SEUIL, SEUIL_JOURS_EXPIRATION_INVITATION));
          LOG.info("gerer:invites-expires terminé relances={} supprimes={}", relances, supprimes);
          return new ResultatPersistance(0, relances + supprimes);
        });
  }

  private void signalerComptesTouches(final int relances, final int supprimes) {
    if (relances + supprimes == 0) {
      return;
    }
    final boolean vague = relances + supprimes >= SEUIL_VAGUE_COMPTES;
    logsAdminWorker.inserer(
        vague ? "warn" : "info",
        "purge-comptes",
        vague
            ? "vague de relances ou suppressions de comptes pour inactivité — vérifier les dates"
                + " de dernière activité"
            : "relance ou suppression de compte pour inactivité",
        Map.of("relances", relances, "supprimes", supprimes));
  }
}
