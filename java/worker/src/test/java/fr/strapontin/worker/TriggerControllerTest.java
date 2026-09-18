/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link TriggerController} — focus sur l'endpoint générique
 * {@code POST /trigger/job} (ajouté pour donner un bouton « Relancer » à chaque
 * job de maintenance dans l'onglet admin Tableau de bord, sans dupliquer un endpoint par job).
 *
 * @since 2.6.0
 * @author Kwaite
 */
class TriggerControllerTest {

  @Test
  void relancerJob_typeJobInconnu_retourne400() {
    final Scheduler scheduler = mock(Scheduler.class);
    final TriggerController controller = new TriggerController(scheduler);

    final ResponseEntity<Map<String, String>> reponse = controller.relancerJob("job:inexistant");

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void relancerJob_reconciliationNocturne_aucuneInstanceEnCours_declenche() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
    final TriggerController controller = new TriggerController(scheduler);

    final ResponseEntity<Map<String, String>> reponse =
        controller.relancerJob("reconciliation:nocturne");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("déclenché", reponse.getBody().get("statut"));
    verify(scheduler, times(1)).triggerJob(eq(JobKey.jobKey("reconciliationNocturne")));
  }

  @Test
  void relancerJob_purgerSalles_declenche() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
    final TriggerController controller = new TriggerController(scheduler);

    final ResponseEntity<Map<String, String>> reponse = controller.relancerJob("purger:salles");

    assertEquals(200, reponse.getStatusCode().value());
    verify(scheduler, times(1)).triggerJob(eq(JobKey.jobKey("purgerSalles")));
  }

  @Test
  void relancerJob_dejaEnCours_neDeclenchePasDeuxiemeFois() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    final JobExecutionContext ctxEnCours = mock(JobExecutionContext.class);
    final var jobDetail =
        JobBuilder.newJob(fr.strapontin.worker.jobs.ReconciliationNocturneJob.class)
            .withIdentity("reconciliationNocturne")
            .build();
    when(ctxEnCours.getJobDetail()).thenReturn(jobDetail);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of(ctxEnCours));
    final TriggerController controller = new TriggerController(scheduler);

    final ResponseEntity<Map<String, String>> reponse =
        controller.relancerJob("reconciliation:nocturne");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("déjà en cours", reponse.getBody().get("statut"));
    verify(scheduler, times(0)).triggerJob(eq(JobKey.jobKey("reconciliationNocturne")));
  }

  @Test
  void relancerJob_erreurQuartz_retourne500() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs())
        .thenThrow(new org.quartz.SchedulerException("boom"));
    final TriggerController controller = new TriggerController(scheduler);

    final ResponseEntity<Map<String, String>> reponse =
        controller.relancerJob("reconciliation:nocturne");

    assertEquals(500, reponse.getStatusCode().value());
  }
}
