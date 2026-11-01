/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProduitDeSpectacleEventimTest {

  private static Map<String, Object> produit(final String lieu, final List<String> racines) {
    final Map<String, Object> produit = new HashMap<>();
    produit.put("name", "Produit");
    produit.put(
        "typeAttributes",
        Map.of(
            "liveEntertainment",
            Map.of("startDate", "2027-07-15T16:00:00+02:00", "location", Map.of("name", lieu))));
    if (racines != null) {
      produit.put("categories", racines.stream().map(nom -> (Object) Map.of("name", nom)).toList());
    }
    return produit;
  }

  @Test
  void estProduitDeSpectacle_concert_retourneVrai() {
    assertTrue(
        MapeurProduitEventim.estProduitDeSpectacle(
            produit("Accor Arena", List.of("Concerts & Festivals"))));
  }

  @Test
  void estProduitDeSpectacle_theatreEtHumour_retourneVrai() {
    assertTrue(MapeurProduitEventim.estProduitDeSpectacle(produit("Essaion", List.of("Théâtre"))));
    assertTrue(
        MapeurProduitEventim.estProduitDeSpectacle(produit("Le Point Virgule", List.of("Humour"))));
  }

  @Test
  void estProduitDeSpectacle_visiteDeMusee_retourneFaux() {
    assertFalse(
        MapeurProduitEventim.estProduitDeSpectacle(
            produit("Institut du Monde Arabe", List.of("Expositions & Musées"))));
  }

  @Test
  void estProduitDeSpectacle_parcDeLoisirsEtMatch_retourneFaux() {
    assertFalse(
        MapeurProduitEventim.estProduitDeSpectacle(produit("Paris", List.of("Parcs & Loisirs"))));
    assertFalse(MapeurProduitEventim.estProduitDeSpectacle(produit("Stade", List.of("Sports"))));
  }

  @Test
  void estProduitDeSpectacle_lieuDeParkingEnCategorieConcert_retourneFaux() {
    assertFalse(
        MapeurProduitEventim.estProduitDeSpectacle(
            produit("PARKING - CHATEAU DE CHANTILLY", List.of("Concerts & Festivals"))));
  }

  @Test
  void estProduitDeSpectacle_sansCategorie_retourneVrai() {
    assertTrue(MapeurProduitEventim.estProduitDeSpectacle(produit("Salle", null)));
  }

  @Test
  void estProduitDeSpectacle_uneCategorieSpectacleParmiPlusieurs_retourneVrai() {
    assertTrue(
        MapeurProduitEventim.estProduitDeSpectacle(
            produit("Salle", List.of("Expositions & Musées", "Spectacles"))));
  }
}
