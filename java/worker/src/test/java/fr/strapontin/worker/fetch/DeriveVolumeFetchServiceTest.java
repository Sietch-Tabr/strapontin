/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.fetch;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.LogsAdminWorker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link DeriveVolumeFetchService}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class DeriveVolumeFetchServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogsAdminWorker logsAdminWorker;
  private DeriveVolumeFetchService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new DeriveVolumeFetchService(jdbc, logsAdminWorker);
  }

  private void stubHistorique(final List<Integer> volumes) {
    when(jdbc.query(
            contains("fetch_jobs_historique"),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<Integer>>any()))
        .thenReturn(volumes);
  }

  @Test
  void verifier_historiqueInsuffisant_neSignalePas() {
    stubHistorique(List.of(100, 100, 100, 100));

    service.verifier("fetch:region", 5, Instant.now());

    verify(logsAdminWorker, never())
        .inserer(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void verifier_volumeCourantProcheDeLaMoyenne_neSignalePas() {
    stubHistorique(List.of(100, 100, 100, 100, 100));

    service.verifier("fetch:region", 90, Instant.now());

    verify(logsAdminWorker, never())
        .inserer(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void verifier_volumeCourantSignificativementInferieur_signaleUnWarn() {
    stubHistorique(List.of(100, 100, 100, 100, 100));

    service.verifier("fetch:region", 50, Instant.now());

    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("warn"),
            org.mockito.ArgumentMatchers.eq("derive-volume"),
            contains("fetch:region"),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void verifier_deuxPicsPeriodiquesDansLHistorique_neSignalePasUnVolumeNormal() {
    // Moyenne 2 500 (seuil 1 750) : le cycle normal à 1 600 serait signalé à tort.
    stubHistorique(List.of(4_000, 1_600, 1_700, 4_100, 1_650));

    service.verifier("fetch:salles:all", 1_600, Instant.now());

    verify(logsAdminWorker, never())
        .inserer(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void verifier_vraieChuteAvecPicsDansLHistorique_signaleUnWarn() {
    stubHistorique(List.of(4_000, 1_600, 1_700, 4_100, 1_650));

    service.verifier("fetch:salles:all", 600, Instant.now());

    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("warn"),
            org.mockito.ArgumentMatchers.eq("derive-volume"),
            contains("fetch:salles:all"),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void verifier_moyenneTropFaible_neSignalePasMemeAvecChuteRelativeImportante() {
    stubHistorique(List.of(2, 2, 2, 2, 2));

    service.verifier("fetch:region", 0, Instant.now());

    verify(logsAdminWorker, never())
        .inserer(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
  }
}
