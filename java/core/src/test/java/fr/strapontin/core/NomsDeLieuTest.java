/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link NomsDeLieu}, sur des noms de lieux publiés en production.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class NomsDeLieuTest {

  @Test
  void designentFortementLeMemeLieu_nomCompleteDeSaVille_vrai() {
    assertTrue(
        NomsDeLieu.designentFortementLeMemeLieu(
            "theatre galli", "theatre galli sanary sur mer", "Sanary-sur-Mer"));
  }

  // « Poche » n'est ni la ville ni un terme générique : un autre théâtre, à 470 m.
  @Test
  void designentFortementLeMemeLieu_nomCompleteDUnMotDistinctif_faux() {
    assertFalse(
        NomsDeLieu.designentFortementLeMemeLieu(
            "theatre montparnasse", "theatre de poche montparnasse paris", "Paris"));
  }

  @Test
  void designentFortementLeMemeLieu_sigleDesInitiales_vrai() {
    assertTrue(NomsDeLieu.designentFortementLeMemeLieu("ed n", "espace dollfus noack", null));
  }

  @Test
  void designentFortementLeMemeLieu_seulMotPivotCommunSansPivot_faux() {
    assertFalse(
        NomsDeLieu.designentFortementLeMemeLieu(
            "elysee montmartre paris 18", "theatre montmartre galabru paris 18", null));
  }

  @Test
  void designentFortementLeMemeLieu_deuxEglisesDistinctes_faux() {
    assertFalse(
        NomsDeLieu.designentFortementLeMemeLieu(
            "eglise saint ephrem paris 05", "eglise saint sulpice paris 06", null));
  }

  @Test
  void designentFortementLeMemeLieu_hallsDistinctsDUnMemeSite_faux() {
    assertFalse(
        NomsDeLieu.designentFortementLeMemeLieu(
            "la grande halle de la villette", "la petite halle de la villette", null));
  }

  @Test
  void designentLeMemeLieu_articleEliseAuMilieuDuNom_vrai() {
    assertTrue(NomsDeLieu.designentLeMemeLieu("le palais d auron", "le palais dauron bourges"));
    assertTrue(
        NomsDeLieu.designentLeMemeLieu("salle de l etoile", "salle de letoile chateaurenard"));
    assertTrue(NomsDeLieu.designentLeMemeLieu("theatre de l eden", "theatre de leden senas"));
  }

  // Palais des Congrès Atlantia : Eventim le publie « Palais des Congrès - La Baule Escoublac ».
  @Test
  void sansMotDistinctif_motsGeneriquesEtVille_vrai() {
    assertTrue(
        NomsDeLieu.sansMotDistinctif(
            "palais des congres la baule escoublac", "La Baule-Escoublac"));
  }

  @Test
  void sansMotDistinctif_autreLieuDeLaVille_faux() {
    assertFalse(NomsDeLieu.sansMotDistinctif("eglise saint louis en lile", "Paris"));
  }

  @Test
  void sansMotDistinctif_villeInconnue_garderLaVille() {
    assertFalse(NomsDeLieu.sansMotDistinctif("palais des congres la baule escoublac", null));
  }
}
