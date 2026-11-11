/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.jobs.FetchArtisteJob;
import fr.strapontin.worker.persister.SimulationResolutionSalles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
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
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.relancerJob("job:inexistant");

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void relancerJob_reconciliationNocturne_aucuneInstanceEnCours_declenche() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

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
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.relancerJob("purger:salles");

    assertEquals(200, reponse.getStatusCode().value());
    verify(scheduler, times(1)).triggerJob(eq(JobKey.jobKey("purgerSalles")));
  }

  @Test
  void relancerJob_dejaEnCours_neDeclenchePasDeuxiemeFois() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    final JobExecutionContext ctxEnCours = mock(JobExecutionContext.class);
    final var jobDetail =
        JobBuilder.newJob(fr.strapontin.worker.jobs.TacheExclusive.class)
            .withIdentity("reconciliationNocturne")
            .build();
    when(ctxEnCours.getJobDetail()).thenReturn(jobDetail);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of(ctxEnCours));
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

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
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse =
        controller.relancerJob("reconciliation:nocturne");

    assertEquals(500, reponse.getStatusCode().value());
  }

  @Test
  void notifierVersion_succes_planifieLeJobEtRetourne200() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.notifierVersion("3.0.0");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("déclenché", reponse.getBody().get("statut"));
    verify(scheduler, times(1))
        .scheduleJob(
            org.mockito.ArgumentMatchers.any(JobDetail.class),
            org.mockito.ArgumentMatchers.any(org.quartz.Trigger.class));
  }

  @Test
  void notifierVersion_erreurQuartz_retourne500() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.scheduleJob(
            org.mockito.ArgumentMatchers.any(JobDetail.class),
            org.mockito.ArgumentMatchers.any(org.quartz.Trigger.class)))
        .thenThrow(new org.quartz.SchedulerException("boom"));
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.notifierVersion("3.0.0");

    assertEquals(500, reponse.getStatusCode().value());
  }

  // Un ajout ne récupère que l'artiste ajouté : le cycle complet vidait le quota Ticketmaster.
  @Test
  void relancerArtistes_avecIdentifiant_planifieLeSeulArtiste() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));
    final String artisteId = "0f8fad5b-d9cb-469f-a165-70867728950e";

    final ResponseEntity<Map<String, String>> reponse = controller.relancerArtistes(artisteId);

    assertEquals(200, reponse.getStatusCode().value());
    final ArgumentCaptor<JobDetail> captor = ArgumentCaptor.forClass(JobDetail.class);
    verify(scheduler).scheduleJob(captor.capture(), any(Trigger.class));
    assertEquals(FetchArtisteJob.class, captor.getValue().getJobClass());
    assertEquals(
        artisteId, captor.getValue().getJobDataMap().getString(FetchArtisteJob.CLE_ARTISTE_ID));
    verify(scheduler, never()).triggerJob(any(JobKey.class));
  }

  @Test
  void relancerArtistes_identifiantInvalide_retourne400() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.relancerArtistes("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
  }

  // L'import de préférences, sans identifiant, garde le cycle complet.
  @Test
  void relancerArtistes_sansIdentifiant_declencheLeCycleComplet() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getCurrentlyExecutingJobs()).thenReturn(List.of());
    final TriggerController controller =
        new TriggerController(scheduler, mock(SimulationResolutionSalles.class));

    final ResponseEntity<Map<String, String>> reponse = controller.relancerArtistes(null);

    assertEquals(200, reponse.getStatusCode().value());
    verify(scheduler).triggerJob(eq(JobKey.jobKey("fetchArtistesBatch")));
  }
}
