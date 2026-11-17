/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.interne;

import fr.strapontin.worker.jobs.FetchArtisteJob;
import fr.strapontin.worker.jobs.FetchSalleJob;
import fr.strapontin.worker.jobs.NotifierVersionJob;
import fr.strapontin.worker.persister.SimulationResolutionSalles;
import fr.strapontin.worker.planification.NomsJobs;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * API interne de déclenchement manuel des jobs Quartz — accessible
 * uniquement depuis localhost (pare-feu/proxy web), n'expose pas de
 * données sensibles.
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/trigger")
public class TriggerController {

  private static final Logger LOG = LoggerFactory.getLogger(TriggerController.class);
  private static final String JOB_FETCH_ARTISTES_BATCH = "fetchArtistesBatch";
  private static final String CLE_STATUT = "statut";
  private static final String STATUT_DEJA_EN_COURS = "déjà en cours";
  private static final String STATUT_DECLENCHE = "déclenché";
  private static final String CLE_ERREUR = "erreur";
  private static final String PREFIXE_ERREUR_QUARTZ = "Erreur Quartz : ";

  private final Scheduler scheduler;
  private final SimulationResolutionSalles simulationResolutionSalles;

  /**
   * @param scheduler planificateur Quartz portant les jobs déclenchables
   * @param simulationResolutionSalles simulation de la résolution par position
   */
  public TriggerController(
      final Scheduler scheduler, final SimulationResolutionSalles simulationResolutionSalles) {
    this.scheduler = scheduler;
    this.simulationResolutionSalles = simulationResolutionSalles;
  }

  /**
   * Simule, sans écrire, la résolution des salles par position sur les
   * données actuelles et en rapporte les écarts avec le rattachement en base.
   * @return effectifs par catégorie et exemples
   */
  @GetMapping("/simulation-salles")
  public ResponseEntity<Map<String, Object>> simulerResolutionSalles() {
    return ResponseEntity.ok(simulationResolutionSalles.simuler());
  }

  /**
   * Retourne la prochaine date d'exécution de chaque job planifié.
   *
   * @return map typeJob → epoch ms de la prochaine exécution
   */
  @GetMapping("/prochaines-exec")
  public ResponseEntity<Map<String, Long>> prochainesExec() {
    final Map<String, Long> resultat = new HashMap<>();
    for (final Map.Entry<String, String> entree : NomsJobs.PAR_CLE_QUARTZ.entrySet()) {
      try {
        scheduler.getTriggersOfJob(JobKey.jobKey(entree.getKey())).stream()
            .map(Trigger::getNextFireTime)
            .filter(Objects::nonNull)
            .mapToLong(Date::getTime)
            .min()
            .ifPresent(t -> resultat.put(entree.getValue(), t));
      } catch (SchedulerException e) {
        LOG.debug("Impossible de lire trigger pour {}", entree.getKey());
      }
    }
    return ResponseEntity.ok(resultat);
  }

  /**
   * Retourne les noms traduits des jobs Quartz en cours d'exécution.
   *
   * @return les noms traduits (ex. {@code "fetch:artistes:batch"} ) des jobs
   *     actifs
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
      LOG.warn("Erreur lecture jobs actifs : {}", e.getMessage());
      return ResponseEntity.ok(Map.of("actifs", List.of()));
    }
  }

  /**
   * Déclenche immédiatement {@code fetch:artistes:batch} et
   * {@code fetch:region} , uniquement si aucun des deux n'est déjà en cours
   * d'exécution.
   *
   * @return 200 si déclenchés ou déjà en cours, 500 en cas d'erreur Quartz
   */
  @PostMapping("/tout")
  public ResponseEntity<Map<String, String>> relancerTout() {
    try {
      if (jobEnCours(JOB_FETCH_ARTISTES_BATCH) || jobEnCours("fetchRegion")) {
        LOG.debug("fetchArtistesBatch ou fetchRegion déjà en cours — ignoré");
        return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DEJA_EN_COURS));
      }
      scheduler.triggerJob(JobKey.jobKey(JOB_FETCH_ARTISTES_BATCH));
      scheduler.triggerJob(JobKey.jobKey("fetchRegion"));
      LOG.info("Déclenchement manuel : fetchArtistesBatch + fetchRegion");
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement manuel : {}", e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }

  /**
   * Après l'ajout d'un artiste, ne récupère que lui ; sans identifiant
   * (import), lance {@code fetch:artistes:batch} s'il n'est pas en cours.
   * @param artisteId identifiant de l'artiste ajouté, ou {@code null}
   * @return 200 déclenché ou déjà en cours, 400 identifiant invalide, 500
   *     erreur Quartz
   */
  @PostMapping("/artiste")
  public ResponseEntity<Map<String, String>> relancerArtistes(
      @RequestParam(required = false) final String artisteId) {
    if (artisteId != null) {
      return relancerUnArtiste(artisteId);
    }
    try {
      if (jobEnCours(JOB_FETCH_ARTISTES_BATCH)) {
        LOG.debug("fetchArtistesBatch déjà en cours — ignoré");
        return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DEJA_EN_COURS));
      }
      scheduler.triggerJob(JobKey.jobKey(JOB_FETCH_ARTISTES_BATCH));
      LOG.info("Déclenchement artiste : fetchArtistesBatch");
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement artiste : {}", e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }

  /**
   * Déclenche un job de maintenance par son {@code typeJob} lisible, si
   * aucune instance n'est déjà en cours — seul endpoint générique pour
   * tout bouton « Relancer » du tableau de bord admin.
   * @param typeJob identifiant lisible du job (voir NomsJobs.PAR_CLE_QUARTZ)
   * @return 200 déclenché/déjà en cours, 400 job inconnu, 500 erreur Quartz
   */
  @PostMapping("/job")
  public ResponseEntity<Map<String, String>> relancerJob(@RequestParam final String typeJob) {
    final String cle = NomsJobs.CLE_QUARTZ_PAR_TYPE_JOB.get(typeJob);
    if (cle == null) {
      return ResponseEntity.badRequest().body(Map.of(CLE_ERREUR, "Job inconnu : " + typeJob));
    }
    try {
      if (jobEnCours(cle)) {
        LOG.debug("{} déjà en cours — ignoré", typeJob);
        return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DEJA_EN_COURS));
      }
      scheduler.triggerJob(JobKey.jobKey(cle));
      LOG.info("Déclenchement manuel : {}", typeJob);
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement {} : {}", typeJob, e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }

  // Un ajout ne récupère que l'artiste ajouté : le cycle complet, lancé à
  // chaque ajout, vidait le quota Ticketmaster.
  private ResponseEntity<Map<String, String>> relancerUnArtiste(final String artisteId) {
    try {
      UUID.fromString(artisteId);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of(CLE_ERREUR, "Identifiant d'artiste invalide"));
    }
    try {
      final String cleJob = "fetchArtiste-" + artisteId;
      if (jobEnCours(cleJob)) {
        LOG.debug("fetchArtiste {} déjà en cours — ignoré", artisteId);
        return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DEJA_EN_COURS));
      }
      final JobDetail jobDetail =
          JobBuilder.newJob(FetchArtisteJob.class)
              .withIdentity(cleJob, "on-demand")
              .usingJobData(FetchArtisteJob.CLE_ARTISTE_ID, artisteId)
              .build();
      scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().startNow().build());
      LOG.info("Déclenchement artiste : fetchArtiste artisteId={}", artisteId);
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement artiste : {}", e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }

  private boolean jobEnCours(final String nomJob) throws SchedulerException {
    return scheduler.getCurrentlyExecutingJobs().stream()
        .map(JobExecutionContext::getJobDetail)
        .anyMatch(jd -> nomJob.equals(jd.getKey().getName()));
  }

  /**
   * Planifie un {@code FetchSalleJob} immédiat, si aucune instance n'est
   * déjà en cours pour ces identifiants (appelé par le web après l'ajout
   * d'une salle).
   * @param identifiants identifiants préfixés des sources (ex. "tm:id")
   * @return 200 si planifié ou déjà en cours, 500 en cas d'erreur Quartz
   */
  @PostMapping("/salle")
  public ResponseEntity<Map<String, String>> relancerSalle(
      @RequestParam final String identifiants) {
    try {
      final String cleJob = "fetchSalle-" + identifiants;
      if (jobEnCours(cleJob)) {
        LOG.debug("fetchSalle {} déjà en cours — ignoré", identifiants);
        return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DEJA_EN_COURS));
      }
      final JobDetail jobDetail =
          JobBuilder.newJob(FetchSalleJob.class)
              .withIdentity(cleJob, "on-demand")
              .usingJobData(FetchSalleJob.CLE_IDENTIFIANTS, identifiants)
              .build();
      scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().startNow().build());
      LOG.info("Déclenchement salle : identifiants={}", identifiants);
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement salle identifiants={} : {}", identifiants, e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }

  /**
   * Planifie un {@code NotifierVersionJob} immédiat (appelé par le web,
   * action admin manuelle) — jamais de vérification de concurrence, un
   * second déclenchement accidentel n'a d'autre effet qu'une notification
   * en double, pas d'incohérence de données.
   * @param version version applicative à annoncer
   * @return 200 si planifié, 500 en cas d'erreur Quartz
   */
  @PostMapping("/notifier-version")
  public ResponseEntity<Map<String, String>> notifierVersion(@RequestParam final String version) {
    try {
      final JobDetail jobDetail =
          JobBuilder.newJob(NotifierVersionJob.class)
              .withIdentity(
                  "notifierVersion-" + version + "-" + System.currentTimeMillis(), "on-demand")
              .usingJobData(NotifierVersionJob.CLE_VERSION, version)
              .build();
      scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().startNow().build());
      LOG.info("Déclenchement notifier:version : version={}", version);
      return ResponseEntity.ok(Map.of(CLE_STATUT, STATUT_DECLENCHE));
    } catch (SchedulerException e) {
      LOG.warn("Erreur déclenchement notifier:version version={} : {}", version, e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of(CLE_ERREUR, PREFIXE_ERREUR_QUARTZ + e.getMessage()));
    }
  }
}
