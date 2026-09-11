/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link SalleHierarchie#creeraitCycle}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SalleHierarchieTest {

  @Test
  void creeraitCycle_candidatParentNull_retourneFalse() {
    assertFalse(SalleHierarchie.creeraitCycle(null, UUID.randomUUID(), _ -> null));
  }

  @Test
  void creeraitCycle_autoReference_retourneTrue() {
    final UUID salleId = UUID.randomUUID();
    assertTrue(SalleHierarchie.creeraitCycle(salleId, salleId, _ -> null));
  }

  @Test
  void creeraitCycle_candidatSansRapportAvecLaSalle_retourneFalse() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    // b -> c (chaîne totalement indépendante de a) : assigner b comme parent de a ne cycle pas
    final Map<UUID, UUID> parents = Map.of(b, c);

    assertFalse(SalleHierarchie.creeraitCycle(b, a, parents::get));
  }

  @Test
  void creeraitCycle_boucleDeuxSalles_retourneTrue() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    // b est déjà parent de a — assigner b comme parent de b (via a) créerait A -> B -> A
    final Map<UUID, UUID> parents = Map.of(a, b);

    assertTrue(SalleHierarchie.creeraitCycle(a, b, parents::get));
  }

  @Test
  void creeraitCycle_candidatDescendantEloigne_retourneTrue() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    // c -> b -> a : assigner c comme parent de a créerait A -> C -> B -> A
    final Map<UUID, UUID> parents = Map.of(c, b, b, a);

    assertTrue(SalleHierarchie.creeraitCycle(c, a, parents::get));
  }
}
