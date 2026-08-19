/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.FetchSallesAllJob;
import fr.strapontin.worker.jobs.NotifierDigestJob;
import fr.strapontin.worker.jobs.PurgerArtistesJob;
import fr.strapontin.worker.jobs.PurgerConcertsJob;
import fr.strapontin.worker.jobs.PurgerLogsAdminJob;
import fr.strapontin.worker.jobs.PurgerSallesJob;
import fr.strapontin.worker.jobs.RecalculerMatchesJob;
import fr.strapontin.worker.jobs.ReconciliationNocturneJob;
import fr.strapontin.worker.jobs.RevoquerAdminsJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Planification Quartz des jobs récurrents.
///
/// Déclencheurs configurés :
///
/// - `fetch:artistes:batch` — 11 cycles/jour : 01h, 05h, puis toutes les 2h de 07h à 23h (cf.
///   `worker.cron.fetch-artistes`)
/// - `fetch:region` — 1 batch nocturne unique à 02h (cf. `worker.cron.fetch-region`) — restreint à
///   ce seul consommateur ([DecouverteService], browse-only, fraîcheur non critique) depuis
///   l'ancien cron adaptatif 11x/jour n'apportant aucune valeur aux artistes/salles suivis
///   (couverts indépendamment par `fetch:artistes:batch`/`fetch:salles:all`)
/// - `fetch:salles:all` — mêmes heures que fetch:artistes + 30 min (cf. `worker.cron.fetch-salles`,
///   fréquence inchangée), restreint aux salles suivies (`user_salles`) depuis
/// - `recalculer:matches` — mêmes heures que fetch:artistes (cf. `worker.cron.recalculer-matches`)
/// - `purger:concerts` — tous les jours à minuit
/// - `purger:salles` — tous les jours à 0h05 (après purge concerts)
/// - `purger:artistes` — tous les jours à 0h06 (après purge salles)
/// - `purger:logs-admin` — tous les jours à 0h15
/// - `notifier:digest` — tous les jours à 8h
/// - `revoquer:admins` — toutes les heures
/// - `reconciliation:nocturne` — tous les jours à 3h
///
/// Le job `fetch:salle` est planifié dynamiquement par [WorkerService] selon les salles
/// configurées. `fetch:salles:all` est le pendant périodique qui boucle sur toutes les salles ayant
/// des sources configurées.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class Planificateur {

  @Value("${worker.cron.fetch-artistes:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchArtistes;

  // cron adaptatif (11x/jour) → 1 batch nocturne unique. DecouverteService est l'unique
  // consommateur de fetch:region (browse-only, sans notification associée) — les artistes/salles
  // suivis ont leurs propres mécanismes indépendants (fetch:artistes:batch, fetch:salles:all),
  // non affectés par cette fréquence.
  @Value("${worker.cron.fetch-region:0 0 2 * * ?}")
  private String cronFetchRegion;

  @Value("${worker.cron.fetch-salles:0 30 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchSalles;

  @Value("${worker.cron.recalculer-matches:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronRecalculerMatches;

  private static JobDetail creerJobDetail(Class<? extends Job> jobClass, String identite) {
    return JobBuilder.newJob(jobClass).withIdentity(identite).storeDurably().build();
  }

  private static Trigger creerTrigger(JobDetail jobDetail, String identite, String cron) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetail)
        .withIdentity(identite)
        .withSchedule(CronScheduleBuilder.cronSchedule(cron))
        .build();
  }

  @Bean
  JobDetail jobDetailFetchArtistesBatch() {
    return creerJobDetail(FetchArtistesBatchJob.class, "fetchArtistesBatch");
  }

  @Bean
  Trigger triggerFetchArtistesBatch(JobDetail jobDetailFetchArtistesBatch) {
    return creerTrigger(
        jobDetailFetchArtistesBatch, "triggerFetchArtistesBatch", cronFetchArtistes);
  }

  @Bean
  JobDetail jobDetailFetchRegion() {
    return creerJobDetail(FetchRegionJob.class, "fetchRegion");
  }

  @Bean
  Trigger triggerFetchRegion(JobDetail jobDetailFetchRegion) {
    return creerTrigger(jobDetailFetchRegion, "triggerFetchRegion", cronFetchRegion);
  }

  @Bean
  JobDetail jobDetailFetchSallesAll() {
    return creerJobDetail(FetchSallesAllJob.class, "fetchSallesAll");
  }

  @Bean
  Trigger triggerFetchSallesAll(JobDetail jobDetailFetchSallesAll) {
    return creerTrigger(jobDetailFetchSallesAll, "triggerFetchSallesAll", cronFetchSalles);
  }

  @Bean
  JobDetail jobDetailRecalculerMatches() {
    return creerJobDetail(RecalculerMatchesJob.class, "recalculerMatches");
  }

  @Bean
  Trigger triggerRecalculerMatches(JobDetail jobDetailRecalculerMatches) {
    return creerTrigger(
        jobDetailRecalculerMatches, "triggerRecalculerMatches", cronRecalculerMatches);
  }

  @Bean
  JobDetail jobDetailPurgerConcerts() {
    return creerJobDetail(PurgerConcertsJob.class, "purgerConcerts");
  }

  @Bean
  Trigger triggerPurgerConcerts(JobDetail jobDetailPurgerConcerts) {
    return creerTrigger(jobDetailPurgerConcerts, "triggerPurgerConcerts", "0 0 0 * * ?");
  }

  @Bean
  JobDetail jobDetailPurgerSalles() {
    return creerJobDetail(PurgerSallesJob.class, "purgerSalles");
  }

  @Bean
  Trigger triggerPurgerSalles(JobDetail jobDetailPurgerSalles) {
    return creerTrigger(jobDetailPurgerSalles, "triggerPurgerSalles", "0 5 0 * * ?");
  }

  @Bean
  JobDetail jobDetailPurgerArtistes() {
    return creerJobDetail(PurgerArtistesJob.class, "purgerArtistes");
  }

  @Bean
  Trigger triggerPurgerArtistes(JobDetail jobDetailPurgerArtistes) {
    return creerTrigger(jobDetailPurgerArtistes, "triggerPurgerArtistes", "0 6 0 * * ?");
  }

  @Bean
  JobDetail jobDetailPurgerLogsAdmin() {
    return creerJobDetail(PurgerLogsAdminJob.class, "purgerLogsAdmin");
  }

  @Bean
  Trigger triggerPurgerLogsAdmin(JobDetail jobDetailPurgerLogsAdmin) {
    return creerTrigger(jobDetailPurgerLogsAdmin, "triggerPurgerLogsAdmin", "0 15 0 * * ?");
  }

  @Bean
  JobDetail jobDetailNotifierDigest() {
    return creerJobDetail(NotifierDigestJob.class, "notifierDigest");
  }

  @Bean
  Trigger triggerNotifierDigest(JobDetail jobDetailNotifierDigest) {
    return creerTrigger(jobDetailNotifierDigest, "triggerNotifierDigest", "0 0 8 * * ?");
  }

  @Bean
  JobDetail jobDetailRevoquerAdmins() {
    return creerJobDetail(RevoquerAdminsJob.class, "revoquerAdmins");
  }

  @Bean
  Trigger triggerRevoquerAdmins(JobDetail jobDetailRevoquerAdmins) {
    return creerTrigger(jobDetailRevoquerAdmins, "triggerRevoquerAdmins", "0 0 * * * ?");
  }

  @Bean
  JobDetail jobDetailReconciliationNocturne() {
    return creerJobDetail(ReconciliationNocturneJob.class, "reconciliationNocturne");
  }

  @Bean
  Trigger triggerReconciliationNocturne(JobDetail jobDetailReconciliationNocturne) {
    return creerTrigger(
        jobDetailReconciliationNocturne, "triggerReconciliationNocturne", "0 0 3 * * ?");
  }
}
