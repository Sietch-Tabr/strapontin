/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link CorrespondanceArtiste#estDoublonArtiste} — dédoublonnage d'artistes.
///
/// <p>Noms passés déjà normalisés (via {@link Normalisation#normaliserTexte}), comme les
/// consommateurs réels de cette classe ({@code ReconciliateurArtistes}, {@code ArtisteService}).
class CorrespondanceArtisteTest {

  @Test
  void estDoublonArtiste_chainesIdentiques_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("radiohead", "radiohead"));
  }

  @Test
  void estDoublonArtiste_sansEspaceJaroSuffisant_retourneTrue() {
    // Jaro("krash karma", "krashkarma") = 0.97 >= seuil 0.95
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("krash karma", "krashkarma"));
  }

  @Test
  void estDoublonArtiste_prefixeCommunJaroInsuffisant_retourneFalse() {
    // Jaro("julien dore", "julien morel") = 0.914 < seuil 0.95, aucun containment applicable
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("julien dore", "julien morel"));
  }

  @Test
  void estDoublonArtiste_troncatureTMCourte_retourneTrue() {
    // "aliz" (4) / "alizee" (6) — ratio 0.667 >= 0.65, containment vérifié
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("aliz", "alizee"));
  }

  @Test
  void estDoublonArtiste_troncatureTMUnCaractere_retourneTrue() {
    // "fawn" (4) / "fawnn" (5) — ratio 0.8 >= 0.65, containment vérifié
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("fawn", "fawnn"));
  }

  @Test
  void estDoublonArtiste_variationLongueSuffixeNumerique_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("star academy", "star academy 1"));
  }

  @Test
  void estDoublonArtiste_containmentSousLongueurMinimale_retourneFalse() {
    // "jo" (2) < LONGUEUR_MIN_CONTAINMENT_ARTISTE (4), même si contenu dans "john"
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("jo", "john"));
  }

  @Test
  void estDoublonArtiste_containmentSousRatioMinimal_retourneFalse() {
    // "abcd" (4) / "abcdefghij" (10) — ratio 0.4 < 0.65, malgré le containment
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("abcd", "abcdefghij"));
  }

  @Test
  void estDoublonArtiste_artistesDistinctsSansRapport_retourneFalse() {
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("radiohead", "coldplay"));
  }

  @Test
  void estDoublonArtiste_premierParametreNull_leveNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> CorrespondanceArtiste.estDoublonArtiste(null, "x"));
  }

  @Test
  void estDoublonArtiste_deuxiemeParametreNull_leveNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> CorrespondanceArtiste.estDoublonArtiste("x", null));
  }
}
