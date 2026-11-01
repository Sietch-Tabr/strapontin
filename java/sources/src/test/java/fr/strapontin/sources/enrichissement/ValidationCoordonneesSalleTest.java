/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationCoordonneesSalleTest {

  private static SalleAEnrichir salle(final String ville, final double lat, final double lon) {
    return new SalleAEnrichir(
        UUID.randomUUID(), "Opera Royal", "opera royal", ville, "78", null, lat, lon);
  }

  // Opéra royal de Versailles : position Ticketmaster à 658 m de l'élément Wikidata.
  @Test
  void incompatibles_memeCommuneEtEcartDeQuelquesCentainesDeMetres_accepte() {
    assertFalse(
        ValidationCoordonneesSalle.incompatibles(
            salle("Versailles", 48.8023, 2.1298), 48.8060, 2.1227, "Versailles"));
  }

  @Test
  void incompatibles_communeDifferenteOuInconnue_gardeLeSeuilDe500Metres() {
    final SalleAEnrichir salle = salle("Versailles", 48.8023, 2.1298);

    assertTrue(ValidationCoordonneesSalle.incompatibles(salle, 48.8060, 2.1227, "Le Chesnay"));
    assertTrue(ValidationCoordonneesSalle.incompatibles(salle, 48.8060, 2.1227, null));
  }

  // Alhambra : deux arrondissements de Paris partagent nom et commune.
  @Test
  void incompatibles_communeAArrondissements_gardeLeSeuilDe500Metres() {
    assertTrue(
        ValidationCoordonneesSalle.incompatibles(
            salle("Paris", 48.8023, 2.1298), 48.8060, 2.1227, "Paris"));
  }

  @Test
  void incompatibles_memeCommuneMaisAuDeLaDuKilometre_rejette() {
    assertTrue(
        ValidationCoordonneesSalle.incompatibles(
            salle("Versailles", 48.8023, 2.1298), 48.8200, 2.1298, "Versailles"));
  }
}
