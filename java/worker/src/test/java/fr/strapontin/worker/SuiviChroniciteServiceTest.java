/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link SuiviChroniciteService}.
 *
 * @since 2.8.2
 * @author Kwaite
 */
class SuiviChroniciteServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private AtomicReference<String> valeurAppConfig;
  private SuiviChroniciteService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    valeurAppConfig = new AtomicReference<>();
    when(jdbc.queryForList(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
            org.mockito.ArgumentMatchers.eq(String.class)))
        .thenAnswer(
            _ ->
                valeurAppConfig.get() == null ? List.<String>of() : List.of(valeurAppConfig.get()));
    when(jdbc.update(
            contains("INSERT INTO app_config"), org.mockito.ArgumentMatchers.<Map<String, ?>>any()))
        .thenAnswer(
            invocation -> {
              final Map<String, ?> params = invocation.getArgument(1);
              valeurAppConfig.set((String) params.get("valeur"));
              return 1;
            });
    service = new SuiviChroniciteService(jdbc, new ObjectMapper());
  }

  @Test
  void enregistrerCycle_premierCycleEnEchec_retourneUn() {
    final int streak = service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);

    assertEquals(1, streak);
  }

  @Test
  void enregistrerCycle_cyclesConsecutifsEnEchec_incrementeLeStreak() {
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    final int streak = service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);

    assertEquals(3, streak);
  }

  @Test
  void enregistrerCycle_cycleReussiApresDesEchecs_reinitialiseAZero() {
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    final int streak = service.enregistrerCycle("fetch:salles:all|Ticketmaster", false);

    assertEquals(0, streak);
  }

  @Test
  void enregistrerCycle_cyclePropreSansHistorique_neContactePasLaBaseEnEcriture() {
    final int streak = service.enregistrerCycle("fetch:salles:all|Ticketmaster", false);

    assertEquals(0, streak);
    verify(jdbc, never())
        .update(
            contains("INSERT INTO app_config"), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
  }

  @Test
  void enregistrerCycle_clesDistinctes_streaksIndependants() {
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    service.enregistrerCycle("fetch:salles:all|Ticketmaster", true);
    final int streakNominatim = service.enregistrerCycle("reconciliation:nocturne|nominatim", true);

    assertEquals(1, streakNominatim);
  }

  @Test
  void enrichirSiChronique_sousLeSeuil_neModifiePasLeMessage() {
    final String message = service.enrichirSiChronique("échec partiel", 2);

    assertEquals("échec partiel", message);
  }

  @Test
  void enrichirSiChronique_auSeuil_ajouteLaMentionDeChronicite() {
    final String message = service.enrichirSiChronique("échec partiel", 3);

    assertEquals("échec partiel — chronique depuis 3 cycles consécutifs", message);
  }
}
