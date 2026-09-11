/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link ControleIntegriteService}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class ControleIntegriteServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SignalementMatchingService signalementMatchingService;
  private ControleIntegriteService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    signalementMatchingService = mock(SignalementMatchingService.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    when(jdbc.query(
            anyString(),
            anyMap(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any()))
        .thenReturn(List.of());
    service = new ControleIntegriteService(jdbc, signalementMatchingService);
  }

  private static Map<String, Object> ligneSalle(final UUID id, final UUID parentId) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", id);
    ligne.put("salle_parent_id", parentId);
    return ligne;
  }

  @Test
  void verifier_cycleSalleParent_signaleLesSallesDuCycle() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    when(jdbc.queryForList(contains("salle_parent_id"), anyMap()))
        .thenReturn(List.of(ligneSalle(a, b), ligneSalle(b, c), ligneSalle(c, a)));

    final int total = service.verifier();

    assertEquals(3, total);
    verify(signalementMatchingService)
        .signaler(eq("cycle_salle_parent"), eq("salle"), eq(a), eq("haute"), anyMap());
    verify(signalementMatchingService)
        .signaler(eq("cycle_salle_parent"), eq("salle"), eq(b), eq("haute"), anyMap());
    verify(signalementMatchingService)
        .signaler(eq("cycle_salle_parent"), eq("salle"), eq(c), eq("haute"), anyMap());
  }

  @Test
  void verifier_hierarchieSansCycle_neSignaleRien() {
    final UUID complexe = UUID.randomUUID();
    final UUID sousSalle = UUID.randomUUID();
    when(jdbc.queryForList(contains("salle_parent_id"), anyMap()))
        .thenReturn(List.of(ligneSalle(complexe, null), ligneSalle(sousSalle, complexe)));

    final int total = service.verifier();

    assertEquals(0, total);
    verify(signalementMatchingService, never())
        .signaler(
            eq("cycle_salle_parent"),
            anyString(),
            org.mockito.ArgumentMatchers.any(),
            anyString(),
            anyMap());
  }

  @Test
  void verifier_deptSalleDivergentDeLaMajorite_signale() {
    final UUID salleId = UUID.randomUUID();
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("salle_id", salleId);
    ligne.put("dept_salle", "75");
    when(jdbc.queryForList(contains("dept_majoritaire"), anyMap())).thenReturn(List.of(ligne));

    final int total = service.verifier();

    assertEquals(1, total);
    verify(signalementMatchingService)
        .signaler(eq("incoherence_dept_salle"), eq("salle"), eq(salleId), eq("basse"), anyMap());
  }

  @Test
  void verifier_evenementOrphelinAncien_signale() {
    final UUID evenementId = UUID.randomUUID();
    when(jdbc.query(
            contains("evenements"),
            anyMap(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any()))
        .thenReturn(List.of(evenementId));

    final int total = service.verifier();

    assertEquals(1, total);
    verify(signalementMatchingService)
        .signaler(
            eq("evenement_orphelin_ancien"),
            eq("evenement"),
            eq(evenementId),
            eq("basse"),
            eq(null));
  }

  @Test
  void verifier_artisteSourceOrpheline_signale() {
    final UUID artisteId = UUID.randomUUID();
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("entite_id", artisteId);
    ligne.put("source", "ticketmaster");
    ligne.put("identifiant_externe", "K8vZ917xxx");
    when(jdbc.queryForList(contains("artiste_sources"), anyMap())).thenReturn(List.of(ligne));

    final int total = service.verifier();

    assertEquals(1, total);
    verify(signalementMatchingService)
        .signaler(eq("source_orpheline"), eq("artiste"), eq(artisteId), eq("basse"), anyMap());
  }

  @Test
  void verifier_salleSourceOrpheline_signale() {
    final UUID salleId = UUID.randomUUID();
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("entite_id", salleId);
    ligne.put("source", "digitick");
    ligne.put("identifiant_externe", "slug-abandonne");
    when(jdbc.queryForList(contains("salle_sources"), anyMap())).thenReturn(List.of(ligne));

    final int total = service.verifier();

    assertEquals(1, total);
    verify(signalementMatchingService)
        .signaler(eq("source_orpheline"), eq("salle"), eq(salleId), eq("basse"), anyMap());
  }

  @Test
  void verifier_aucuneAnomalie_neSignaleRienEtRetourneZero() {
    final int total = service.verifier();

    assertEquals(0, total);
  }
}
