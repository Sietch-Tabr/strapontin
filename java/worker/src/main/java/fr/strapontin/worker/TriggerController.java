/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import java.util.Map;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// API interne de déclenchement manuel des jobs Quartz.
///
/// <p>Accessible uniquement depuis {@code localhost} (filtrée par le pare-feu ou
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
}
