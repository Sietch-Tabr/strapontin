/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchSalleJob;
import java.util.Map;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// API interne de déclenchement manuel des jobs Quartz.
///
/// Accessible uniquement depuis `localhost` (filtrée par le pare-feu ou
/// le proxy web). N'expose pas de données sensibles.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/trigger")
public class TriggerController {

  private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

  private final Scheduler scheduler;

  public TriggerController(Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  /// Déclenche immédiatement {@code fetch:artistes:batch} et {@code fetch:region}.
  ///
  /// @return 200 si les deux jobs ont été déclenchés, 500 en cas d'erreur Quartz
  @PostMapping("/tout")
  public ResponseEntity<Map<String, String>> relancerTout() {
    try {
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

  /// Déclenche immédiatement {@code fetch:artistes:batch} uniquement.
  ///
  /// <p>Appelé automatiquement par le web après l'ajout d'un artiste.
  ///
  /// @return 200 si déclenché, 500 en cas d'erreur Quartz
  @PostMapping("/artiste")
  public ResponseEntity<Map<String, String>> relancerArtistes() {
    try {
      scheduler.triggerJob(JobKey.jobKey("fetchArtistesBatch"));
      log.info("Déclenchement artiste : fetchArtistesBatch");
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement artiste", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }

  /// Planifie un {@code FetchSalleJob} immédiat pour le {@code venueId} donné.
  ///
  /// <p>Appelé automatiquement par le web après l'ajout d'une salle avec venueId TM.
  ///
  /// @param venueId identifiant Ticketmaster de la salle
  /// @return 200 si planifié, 500 en cas d'erreur Quartz
  @PostMapping("/salle/{venueId}")
  public ResponseEntity<Map<String, String>> relancerSalle(@PathVariable String venueId) {
    try {
      JobDetail jobDetail =
          JobBuilder.newJob(FetchSalleJob.class)
              .withIdentity("fetchSalle-" + venueId, "on-demand")
              .usingJobData(FetchSalleJob.CLE_VENUE_ID, venueId)
              .build();
      scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().startNow().build());
      log.info("Déclenchement salle : venueId={}", venueId);
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (SchedulerException e) {
      log.warn("Erreur déclenchement salle venueId={}", venueId, e);
      return ResponseEntity.internalServerError()
          .body(Map.of("erreur", "Erreur Quartz : " + e.getMessage()));
    }
  }
}
