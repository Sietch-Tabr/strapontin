/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

/**
 * Tests unitaires pour {@link RetryLotsService}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class RetryLotsServiceTest {

  private Scheduler scheduler;
  private SuiviChroniciteService suiviChronicite;
  private LogsAdminWorker logsAdminWorker;
  private RetryLotsService service;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = mock(Scheduler.class);
    suiviChronicite = mock(SuiviChroniciteService.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new RetryLotsService(scheduler, suiviChronicite, logsAdminWorker);
  }

  @Test
  void gererResultat_typeJobNonConcerne_neFaitRien() throws Exception {
    service.gererResultat("purger:concerts", List.of("x"));

    verify(suiviChronicite, never())
        .enregistrerCycle(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(scheduler, never()).scheduleJob(org.mockito.ArgumentMatchers.any(Trigger.class));
  }

  @Test
  void gererResultat_cyclePropre_remetLeCompteurAZeroSansPlanifier() throws Exception {
    service.gererResultat("fetch:region", List.of());

    verify(suiviChronicite).enregistrerCycle("retry:fetch:region", false);
    verify(scheduler, never()).scheduleJob(org.mockito.ArgumentMatchers.any(Trigger.class));
  }

  @Test
  void gererResultat_premierEchec_planifieUnRetryDans15Minutes() throws Exception {
    when(suiviChronicite.enregistrerCycle("retry:fetch:artistes:batch", true)).thenReturn(1);

    service.gererResultat("fetch:artistes:batch", List.of("artiste-1", "artiste-2"));

    final ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(scheduler).scheduleJob(captor.capture());
    final Trigger trigger = captor.getValue();
    assertEquals(JobKey.jobKey("fetchArtistesBatch"), trigger.getJobKey());
    assertEquals(
        "artiste-1,artiste-2",
        trigger.getJobDataMap().getString(FetchArtistesBatchJob.CLE_IDS_RETRY));
    final long delaiMs = trigger.getStartTime().getTime() - System.currentTimeMillis();
    assertTrue(delaiMs > 13 * 60 * 1000 && delaiMs < 16 * 60 * 1000);
    verify(logsAdminWorker, never())
        .inserer(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void gererResultat_streakAtteintLeSeuilChronique_abandonneSansPlanifierEtJournalise()
      throws Exception {
    when(suiviChronicite.enregistrerCycle("retry:fetch:salles:all", true))
        .thenReturn(SuiviChroniciteService.SEUIL_CYCLES_CHRONIQUE);
    when(suiviChronicite.enrichirSiChronique(
            anyString(), eq(SuiviChroniciteService.SEUIL_CYCLES_CHRONIQUE)))
        .thenReturn("message chronique");

    service.gererResultat("fetch:salles:all", List.of("salle-1"));

    verify(scheduler, never()).scheduleJob(org.mockito.ArgumentMatchers.any(Trigger.class));
    verify(logsAdminWorker)
        .inserer(
            eq("warn"), eq("worker"), contains("chronique"), org.mockito.ArgumentMatchers.any());
  }
}
