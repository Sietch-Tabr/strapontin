/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.HorairesJobs;
import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.FetchSallesAllJob;
import fr.strapontin.worker.jobs.TachesPlanifiees;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Planification Quartz des jobs récurrents — détail des cadences :
 * CLAUDE.md, Jobs background. {@code fetch:salle} est planifié
 * dynamiquement par {@link WorkerService} selon les salles configurées.
 * @since 2.0.0
 * @author Kwaite
 */
@Configuration
public class Planificateur {

  /* package-private : fuseau des triggers, partagé avec PlanificateurNotifications. */
  static final TimeZone PARIS = TimeZone.getTimeZone(HorairesJobs.FUSEAU);

  @Value("${worker.cron.fetch-artistes:" + HorairesJobs.FETCH_ARTISTES + "}")
  private String cronFetchArtistes;

  // cron adaptatif (11x/jour) → 1 batch nocturne unique. DecouverteService est
  // l'unique consommateur de fetch:region (browse-only, sans notification
  // associée) — les artistes/salles suivis ont leurs propres mécanismes
  // indépendants (fetch:artistes:batch, fetch:salles:all), non affectés par
  // cette fréquence.
  @Value("${worker.cron.fetch-region:" + HorairesJobs.FETCH_REGION + "}")
  private String cronFetchRegion;

  @Value("${worker.cron.fetch-salles:" + HorairesJobs.FETCH_SALLES + "}")
  private String cronFetchSalles;

  // Une lecture par jour, en journée : un concert découvert la nuit pourrait
  // réveiller un utilisateur en fréquence « à chaque nouveau concert ».
  @Value("${worker.cron.fetch-sites-salles:" + HorairesJobs.FETCH_SITES_SALLES + "}")
  private String cronFetchSitesSalles;

  @Value("${worker.cron.recalculer-matches:" + HorairesJobs.RECALCULER_MATCHES + "}")
  private String cronRecalculerMatches;

  @Value("${worker.cron.reconciliation-nocturne:" + HorairesJobs.RECONCILIATION_NOCTURNE + "}")
  private String cronReconciliationNocturne;

  /** Constructeur par défaut. */
  public Planificateur() {}

  /* package-private : partagé avec PlanificateurNotifications (même package). */
  static JobDetail creerJobDetail(final Class<? extends Job> jobClass, final String identite) {
    return JobBuilder.newJob(jobClass).withIdentity(identite).storeDurably().build();
  }

  /* package-private : partagé avec PlanificateurNotifications (même package). */
  static Trigger creerTrigger(final JobDetail jobDetail, final String identite, final String cron) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetail)
        .withIdentity(identite)
        .withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(PARIS))
        .build();
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailFetchArtistesBatch() {
    return creerJobDetail(FetchArtistesBatchJob.class, "fetchArtistesBatch");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerFetchArtistesBatch(final JobDetail jobDetailFetchArtistesBatch) {
    return creerTrigger(
        jobDetailFetchArtistesBatch, "triggerFetchArtistesBatch", cronFetchArtistes);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailFetchRegion() {
    return creerJobDetail(FetchRegionJob.class, "fetchRegion");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerFetchRegion(final JobDetail jobDetailFetchRegion) {
    return creerTrigger(jobDetailFetchRegion, "triggerFetchRegion", cronFetchRegion);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailFetchSallesAll() {
    return creerJobDetail(FetchSallesAllJob.class, "fetchSallesAll");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerFetchSallesAll(final JobDetail jobDetailFetchSallesAll) {
    return creerTrigger(jobDetailFetchSallesAll, "triggerFetchSallesAll", cronFetchSalles);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailFetchSitesSalles() {
    return TachesPlanifiees.jobDetail("fetchSitesSalles");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerFetchSitesSalles(final JobDetail jobDetailFetchSitesSalles) {
    return creerTrigger(jobDetailFetchSitesSalles, "triggerFetchSitesSalles", cronFetchSitesSalles);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailRecalculerMatches() {
    return TachesPlanifiees.jobDetail("recalculerMatches");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerRecalculerMatches(final JobDetail jobDetailRecalculerMatches) {
    return creerTrigger(
        jobDetailRecalculerMatches, "triggerRecalculerMatches", cronRecalculerMatches);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerConcerts() {
    return TachesPlanifiees.jobDetail("purgerConcerts");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerConcerts(final JobDetail jobDetailPurgerConcerts) {
    return creerTrigger(
        jobDetailPurgerConcerts, "triggerPurgerConcerts", HorairesJobs.PURGER_CONCERTS);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerSalles() {
    return TachesPlanifiees.jobDetail("purgerSalles");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerSalles(final JobDetail jobDetailPurgerSalles) {
    return creerTrigger(jobDetailPurgerSalles, "triggerPurgerSalles", HorairesJobs.PURGER_SALLES);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerArtistes() {
    return TachesPlanifiees.jobDetail("purgerArtistes");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerArtistes(final JobDetail jobDetailPurgerArtistes) {
    return creerTrigger(
        jobDetailPurgerArtistes, "triggerPurgerArtistes", HorairesJobs.PURGER_ARTISTES);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerLogsAdmin() {
    return TachesPlanifiees.jobDetail("purgerLogsAdmin");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerLogsAdmin(final JobDetail jobDetailPurgerLogsAdmin) {
    return creerTrigger(
        jobDetailPurgerLogsAdmin, "triggerPurgerLogsAdmin", HorairesJobs.PURGER_LOGS_ADMIN);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailGererComptesInactifs() {
    return TachesPlanifiees.jobDetail("gererComptesInactifs");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerGererComptesInactifs(final JobDetail jobDetailGererComptesInactifs) {
    return creerTrigger(
        jobDetailGererComptesInactifs,
        "triggerGererComptesInactifs",
        HorairesJobs.COMPTES_INACTIFS);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailGererInvitesExpires() {
    return TachesPlanifiees.jobDetail("gererInvitesExpires");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerGererInvitesExpires(final JobDetail jobDetailGererInvitesExpires) {
    return creerTrigger(
        jobDetailGererInvitesExpires, "triggerGererInvitesExpires", HorairesJobs.INVITES_EXPIRES);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailRevoquerAdmins() {
    return TachesPlanifiees.jobDetail("revoquerAdmins");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerRevoquerAdmins(final JobDetail jobDetailRevoquerAdmins) {
    return creerTrigger(
        jobDetailRevoquerAdmins, "triggerRevoquerAdmins", HorairesJobs.REVOQUER_ADMINS);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailReconciliationNocturne() {
    return TachesPlanifiees.jobDetail("reconciliationNocturne");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerReconciliationNocturne(final JobDetail jobDetailReconciliationNocturne) {
    return creerTrigger(
        jobDetailReconciliationNocturne,
        "triggerReconciliationNocturne",
        cronReconciliationNocturne);
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailSuiviCyclesJobs() {
    return TachesPlanifiees.jobDetail("suiviCyclesJobs");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerSuiviCyclesJobs(final JobDetail jobDetailSuiviCyclesJobs) {
    return creerTrigger(
        jobDetailSuiviCyclesJobs, "triggerSuiviCyclesJobs", HorairesJobs.SUIVI_CYCLES);
  }
}
