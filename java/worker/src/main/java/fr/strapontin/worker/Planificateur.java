/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.FetchSallesAllJob;
import fr.strapontin.worker.jobs.FetchSitesSallesJob;
import fr.strapontin.worker.jobs.GererComptesInactifsJob;
import fr.strapontin.worker.jobs.GererInvitesExpiresJob;
import fr.strapontin.worker.jobs.PurgerArtistesJob;
import fr.strapontin.worker.jobs.PurgerConcertsJob;
import fr.strapontin.worker.jobs.PurgerLogsAdminJob;
import fr.strapontin.worker.jobs.PurgerSallesJob;
import fr.strapontin.worker.jobs.RecalculerMatchesJob;
import fr.strapontin.worker.jobs.ReconciliationNocturneJob;
import fr.strapontin.worker.jobs.RevoquerAdminsJob;
import fr.strapontin.worker.jobs.SuiviCyclesJobsJob;
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

  @Value("${worker.cron.fetch-artistes:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchArtistes;

  // cron adaptatif (11x/jour) → 1 batch nocturne unique. DecouverteService est
  // l'unique consommateur de fetch:region (browse-only, sans notification
  // associée) — les artistes/salles suivis ont leurs propres mécanismes
  // indépendants (fetch:artistes:batch, fetch:salles:all), non affectés par
  // cette fréquence.
  @Value("${worker.cron.fetch-region:0 0 2 * * ?}")
  private String cronFetchRegion;

  @Value("${worker.cron.fetch-salles:0 30 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchSalles;

  // Une lecture par jour, en journée : un concert découvert la nuit pourrait
  // réveiller un utilisateur en fréquence « à chaque nouveau concert ».
  @Value("${worker.cron.fetch-sites-salles:0 0 14 * * ?}")
  private String cronFetchSitesSalles;

  @Value("${worker.cron.recalculer-matches:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronRecalculerMatches;

  @Value("${worker.cron.reconciliation-nocturne:0 0 3 * * ?}")
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
        .withSchedule(CronScheduleBuilder.cronSchedule(cron))
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
    return creerJobDetail(FetchSitesSallesJob.class, "fetchSitesSalles");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerFetchSitesSalles(final JobDetail jobDetailFetchSitesSalles) {
    // Fuseau explicite : « en journée » vaut pour Paris, quel que soit le fuseau
    // du serveur.
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchSitesSalles)
        .withIdentity("triggerFetchSitesSalles")
        .withSchedule(
            CronScheduleBuilder.cronSchedule(cronFetchSitesSalles)
                .inTimeZone(TimeZone.getTimeZone("Europe/Paris")))
        .build();
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailRecalculerMatches() {
    return creerJobDetail(RecalculerMatchesJob.class, "recalculerMatches");
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
    return creerJobDetail(PurgerConcertsJob.class, "purgerConcerts");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerConcerts(final JobDetail jobDetailPurgerConcerts) {
    return creerTrigger(jobDetailPurgerConcerts, "triggerPurgerConcerts", "0 0 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerSalles() {
    return creerJobDetail(PurgerSallesJob.class, "purgerSalles");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerSalles(final JobDetail jobDetailPurgerSalles) {
    return creerTrigger(jobDetailPurgerSalles, "triggerPurgerSalles", "0 5 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerArtistes() {
    return creerJobDetail(PurgerArtistesJob.class, "purgerArtistes");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerArtistes(final JobDetail jobDetailPurgerArtistes) {
    return creerTrigger(jobDetailPurgerArtistes, "triggerPurgerArtistes", "0 6 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailPurgerLogsAdmin() {
    return creerJobDetail(PurgerLogsAdminJob.class, "purgerLogsAdmin");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerPurgerLogsAdmin(final JobDetail jobDetailPurgerLogsAdmin) {
    return creerTrigger(jobDetailPurgerLogsAdmin, "triggerPurgerLogsAdmin", "0 15 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailGererComptesInactifs() {
    return creerJobDetail(GererComptesInactifsJob.class, "gererComptesInactifs");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerGererComptesInactifs(final JobDetail jobDetailGererComptesInactifs) {
    return creerTrigger(
        jobDetailGererComptesInactifs, "triggerGererComptesInactifs", "0 20 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailGererInvitesExpires() {
    return creerJobDetail(GererInvitesExpiresJob.class, "gererInvitesExpires");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerGererInvitesExpires(final JobDetail jobDetailGererInvitesExpires) {
    return creerTrigger(jobDetailGererInvitesExpires, "triggerGererInvitesExpires", "0 25 0 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailRevoquerAdmins() {
    return creerJobDetail(RevoquerAdminsJob.class, "revoquerAdmins");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerRevoquerAdmins(final JobDetail jobDetailRevoquerAdmins) {
    return creerTrigger(jobDetailRevoquerAdmins, "triggerRevoquerAdmins", "0 0 * * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailReconciliationNocturne() {
    return creerJobDetail(ReconciliationNocturneJob.class, "reconciliationNocturne");
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
    return creerJobDetail(SuiviCyclesJobsJob.class, "suiviCyclesJobs");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerSuiviCyclesJobs(final JobDetail jobDetailSuiviCyclesJobs) {
    return creerTrigger(jobDetailSuiviCyclesJobs, "triggerSuiviCyclesJobs", "0 30 * * * ?");
  }
}
