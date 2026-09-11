/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchSalleJob;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API interne de déclenchement manuel des jobs Quartz.
 *
 * Accessible uniquement depuis {@code localhost} (filtrée par le pare-feu ou le proxy web). N'expose pas
 * de données sensibles.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/trigger")
public class TriggerController {

  private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

  private final Scheduler scheduler;

  public TriggerController(final Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  /**
   * Retourne la prochaine date d'exécution de chaque job planifié.
   *
   * @return map typeJob → epoch ms de la prochaine exécution
   */
  @GetMapping("/prochaines-exec")
  public ResponseEntity<Map<String, Long>> prochainesExec() {
    final Map<String, Long> resultat = new HashMap<>();
    for (Map.Entry<String, String> entree : NomsJobs.PAR_CLE_QUARTZ.entrySet()) {
      try {
        scheduler.getTriggersOfJob(JobKey.jobKey(entree.getKey())).stream()
            .map(Trigger::getNextFireTime)
            .filter(Objects::nonNull)
            .mapToLong(Date::getTime)
            .min()
            .ifPresent(t -> resultat.put(entree.getValue(), t));
      } catch (SchedulerException e) {
        log.debug("Impossible de lire trigger pour {}", entree.getKey());
      }
    }
    return ResponseEntity.ok(resultat);
  }

  /**
   * Retourne les noms traduits des jobs Quartz en cours d'exécution.
   *
   * @return les noms traduits (ex. {@code "fetch:artistes:batch"}) des jobs actifs
   */
  @GetMapping("/actif")
  public ResponseEntity<Map<String, Object>> jobsActifs() {
    try {
      final List<String> actifs =
          scheduler.getCurrentlyExecutingJobs().stream()
              .map(
                  ctx ->
                      NomsJobs.PAR_CLE_QUARTZ.getOrDefault(
                          ctx.getJobDetail().getKey().getName(),
                          ctx.getJobDetail().getKey().getName()))
              .toList();
      return ResponseEntity.ok(Map.of("actifs", actifs));
    } catch (SchedulerException e) {
      log.warn("Erreur lecture jobs actifs", e);
      return ResponseEntity.ok(Map.of("actifs", List.of()));
    }
  }

  /**
   * Déclenche immédiatement {@code fetch:artistes:batch} et {@code fetch:region}, uniquement si
   * aucun des deux n'est déjà en cours d'exécution.
   *
   * @return 200 si déclenchés ou déjà en cours, 500 en cas d'erreur Quartz
   */
  @PostMapping("/tout")
  public ResponseEntity<Map<String, String>> relancerTout() {
    try {
      if (jobEnCours("fetchArtistesBatch") || jobEnCours("fetchRegion")) {
        log.debug("fetchArtistesBatch ou fetchRegion déjà en cours — ignoré");
        return ResponseEntity.ok(Map.of("statut", "déjà en cours"));
      }
      scheduler.triggerJob(JobKey.jobKey("fetchArtistesBatch"));
      scheduler.triggerJob(JobKey.jobKey("fetchRegion"));
      log.info("Déclenchement manuel : fetchArtistesBatch + fetchRegion");
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement manuel", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }

  /**
   * Déclenche immédiatement {@code fetch:artistes:batch} uniquement si aucun n'est déjà en cours.
   *
   * <p>Appelé automatiquement par le web après l'ajout d'un artiste.
   *
   * @return 200 si déclenché ou déjà en cours, 500 en cas d'erreur Quartz
   */
  @PostMapping("/artiste")
  public ResponseEntity<Map<String, String>> relancerArtistes() {
    try {
      if (jobEnCours("fetchArtistesBatch")) {
        log.debug("fetchArtistesBatch déjà en cours — ignoré");
        return ResponseEntity.ok(Map.of("statut", "déjà en cours"));
      }
      scheduler.triggerJob(JobKey.jobKey("fetchArtistesBatch"));
      log.info("Déclenchement artiste : fetchArtistesBatch");
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement artiste", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }

  /**
   * Déclenche immédiatement un job de maintenance identifié par son {@code typeJob} lisible (ex.
   * {@code "purger:salles"}, {@code "reconciliation:nocturne"}), uniquement si aucune instance
   * n'est déjà en cours. Un seul endpoint générique pour tous les jobs à bouton « Relancer » de
   * l'onglet admin Tableau de bord — éviter d'en dupliquer un par job.
   *
   * @param typeJob identifiant lisible du job (clé de {@link NomsJobs#PAR_CLE_QUARTZ})
   * @return 200 si déclenché ou déjà en cours, 400 si job inconnu, 500 en cas d'erreur Quartz
   */
  @PostMapping("/job")
  public ResponseEntity<Map<String, String>> relancerJob(@RequestParam final String typeJob) {
    final String cle = NomsJobs.CLE_QUARTZ_PAR_TYPE_JOB.get(typeJob);
    if (cle == null) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Job inconnu : " + typeJob));
    }
    try {
      if (jobEnCours(cle)) {
        log.debug("{} déjà en cours — ignoré", typeJob);
        return ResponseEntity.ok(Map.of("statut", "déjà en cours"));
      }
      scheduler.triggerJob(JobKey.jobKey(cle));
      log.info("Déclenchement manuel : {}", typeJob);
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement {}", typeJob, e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }

  private boolean jobEnCours(final String nomJob) throws SchedulerException {
    return scheduler.getCurrentlyExecutingJobs().stream()
        .map(JobExecutionContext::getJobDetail)
        .anyMatch(jd -> nomJob.equals(jd.getKey().getName()));
  }

  /**
   * Planifie un {@code FetchSalleJob} immédiat pour les identifiants donnés, uniquement si aucune
   * instance n'est déjà en cours pour ce jeu d'identifiants.
   *
   * <p>Appelé automatiquement par le web après l'ajout d'une salle.
   *
   * @param identifiants identifiants préfixés des sources (ex. {@code "tm:rZFSnyZdFvA"})
   * @return 200 si planifié ou déjà en cours, 500 en cas d'erreur Quartz
   */
  @PostMapping("/salle")
  public ResponseEntity<Map<String, String>> relancerSalle(@RequestParam final String identifiants) {
    try {
      final String cleJob = "fetchSalle-" + identifiants;
      if (jobEnCours(cleJob)) {
        log.debug("fetchSalle {} déjà en cours — ignoré", identifiants);
        return ResponseEntity.ok(Map.of("statut", "déjà en cours"));
      }
      final JobDetail jobDetail =
          JobBuilder.newJob(FetchSalleJob.class)
              .withIdentity(cleJob, "on-demand")
              .usingJobData(FetchSalleJob.CLE_IDENTIFIANTS, identifiants)
              .build();
      scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().startNow().build());
      log.info("Déclenchement salle : identifiants={}", identifiants);
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement salle identifiants={}", identifiants, e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }
}
