/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapeurProduitEventimTitreDuLieuTest {

  private static Map<String, Object> produit(
      final String titre, final String lieu, final List<Map<String, String>> attractions) {
    return Map.of(
        "name",
        titre,
        "link",
        "https://www.eventim.fr/event/x-22000001",
        "attractions",
        attractions,
        "typeAttributes",
        Map.of(
            "liveEntertainment",
            Map.of(
                "startDate",
                "2098-03-05T20:00:00+01:00",
                "location",
                Map.of("name", lieu, "city", "Paris", "postalCode", "75006"))));
  }

  // Église Saint-Germain-des-Prés : le titre du produit est le nom du lieu.
  @Test
  void mapper_titreEgalAuNomDuLieu_prendLEnsembleQuiJoue() {
    final ConcertBrut concert =
        MapeurProduitEventim.mapper(
            produit(
                "Eglise St Germain des Prés",
                "EGLISE SAINT GERMAIN DES PRES PARIS 06",
                List.of(Map.of("name", "Orchestre Paris Classik"), Map.of("name", "Autre"))),
            null,
            null);

    assertEquals("Orchestre Paris Classik", concert.artiste());
  }

  @Test
  void mapper_titreEgalAuNomDuLieuSansAttraction_gardeLeTitre() {
    final ConcertBrut concert =
        MapeurProduitEventim.mapper(
            produit(
                "Eglise St Germain des Prés", "EGLISE SAINT GERMAIN DES PRES PARIS 06", List.of()),
            null,
            null);

    assertEquals("Eglise St Germain des Prés", concert.artiste());
  }

  @Test
  void mapper_titreDUnSpectacle_resteLArtiste() {
    final ConcertBrut concert =
        MapeurProduitEventim.mapper(
            produit(
                "Vivaldi : Les Quatre Saisons",
                "EGLISE SAINT GERMAIN DES PRES PARIS 06",
                List.of(Map.of("name", "Orchestre Paris Classik"))),
            null,
            null);

    assertEquals("Vivaldi", concert.artiste());
  }
}
