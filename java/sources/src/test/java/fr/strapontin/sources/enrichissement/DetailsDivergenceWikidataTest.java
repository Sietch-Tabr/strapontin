/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DetailsDivergenceWikidataTest {

  private static SalleAEnrichir salleLyon() {
    return new SalleAEnrichir(
        UUID.randomUUID(), "L'Amphitheatre", "l amphitheatre", "Lyon", "69", null, 45.76, 4.83);
  }

  @Test
  void detailsASignaler_elementAQuelquesCentainesDeMetres_retourneLesDetails() {
    final var details =
        DetailsDivergenceWikidata.detailsASignaler(
            salleLyon(), "http://www.wikidata.org/entity/Q3202774", "L'Axone", 45.77, 4.83);

    assertTrue(details.isPresent());
    assertEquals("Q3202774", details.get().get("wikidataId"));
  }

  @Test
  void detailsASignaler_elementDesTerresAustrales_neDemandeAucuneRevue() {
    final var details =
        DetailsDivergenceWikidata.detailsASignaler(
            salleLyon(), "http://www.wikidata.org/entity/Q31454553", "Amphitheatre", -49.3, 69.5);

    assertTrue(details.isEmpty());
  }
}
