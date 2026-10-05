/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link ReconciliateurSalles} — façade de délégation pure vers {@link
 * ReconciliationNomsSalles}, {@link ResolveurSalleParConcert} et {@link
 * ReconciliateurSallesOrphelines} (v2.8.7). Le comportement propre à chaque responsabilité est
 * testé dans {@code ReconciliationNomsSallesTest}, {@code ResolveurSalleParConcertTest} et {@code
 * ReconciliateurSallesOrphelinesTest}.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class ReconciliateurSallesTest {

  private ReconciliationNomsSalles reconciliationNoms;
  private ResolveurSalleParConcert resolveurSalle;
  private ReconciliateurSallesOrphelines reconciliateurOrphelines;
  private ReconciliateurSalles reconciliateur;

  @BeforeEach
  void setUp() {
    reconciliationNoms = mock(ReconciliationNomsSalles.class);
    resolveurSalle = mock(ResolveurSalleParConcert.class);
    reconciliateurOrphelines = mock(ReconciliateurSallesOrphelines.class);
    reconciliateur =
        new ReconciliateurSalles(reconciliationNoms, resolveurSalle, reconciliateurOrphelines);
  }

  private static ValeurConcert concert() {
    return FabriqueValeurConcert.nouveau()
        .artiste("Artiste")
        .artisteNorm("artiste")
        .date(LocalDate.of(2026, 9, 1))
        .salle("Salle")
        .salleNorm("salle")
        .source("ticketmaster")
        .construire();
  }

  @Test
  void reconcilierSalles_delegueAReconciliationNomsSalles() {
    final List<ValeurConcert> lot = List.of(concert());
    when(reconciliationNoms.reconcilierSalles(lot)).thenReturn(lot);

    final List<ValeurConcert> resultat = reconciliateur.reconcilierSalles(lot);

    assertEquals(lot, resultat);
    verify(reconciliationNoms).reconcilierSalles(lot);
  }

  @Test
  void absorberRenommagesSalles_delegueAReconciliationNomsSalles() {
    final List<ValeurConcert> concerts = List.of(concert());
    final Map<String, UUID> caches = Map.of("tm:1", UUID.randomUUID());
    final Map<UUID, String> deptsConnus = Map.of();

    reconciliateur.absorberRenommagesSalles(concerts, caches, deptsConnus);

    verify(reconciliationNoms).absorberRenommagesSalles(concerts, caches, deptsConnus);
  }

  @Test
  void upsertSalleSource_delegueAuResolveur() {
    final ValeurConcert c = concert();
    final UUID salleId = UUID.randomUUID();
    when(resolveurSalle.upsertSalleSource(c)).thenReturn(salleId);

    final UUID resultat = reconciliateur.upsertSalleSource(c);

    assertEquals(salleId, resultat);
  }

  @Test
  void chercherSalleParTokens_delegueAuResolveur() {
    final UUID salleId = UUID.randomUUID();
    when(resolveurSalle.chercherSalleParTokens("Salle", "75")).thenReturn(salleId);

    final UUID resultat = reconciliateur.chercherSalleParTokens("Salle", "75");

    assertEquals(salleId, resultat);
  }

  @Test
  void chercherSalleParCleForte_delegueAuResolveur() {
    final ValeurConcert c = concert();
    final UUID salleId = UUID.randomUUID();
    when(resolveurSalle.chercherSalleParCleForte(c)).thenReturn(salleId);

    final UUID resultat = reconciliateur.chercherSalleParCleForte(c);

    assertEquals(salleId, resultat);
  }

  @Test
  void raccrocherSallesParNomNorm_delegueAuResolveur() {
    final List<ValeurConcert> concerts = List.of(concert());
    when(resolveurSalle.raccrocherSallesParNomNorm(concerts)).thenReturn(concerts);

    final List<ValeurConcert> resultat = reconciliateur.raccrocherSallesParNomNorm(concerts);

    assertEquals(concerts, resultat);
  }

  @Test
  void deptsConnus_delegueAuResolveur() {
    final UUID salleId = UUID.randomUUID();
    when(resolveurSalle.deptsConnus(Set.of(salleId))).thenReturn(Map.of(salleId, "75"));

    final Map<UUID, String> resultat = reconciliateur.deptsConnus(Set.of(salleId));

    assertEquals(Map.of(salleId, "75"), resultat);
  }

  @Test
  void retenterSallesOrphelines_delegueAuReconciliateurOrphelines() {
    when(reconciliateurOrphelines.retenterSallesOrphelines()).thenReturn(3);

    final int resultat = reconciliateur.retenterSallesOrphelines();

    assertEquals(3, resultat);
  }
}
