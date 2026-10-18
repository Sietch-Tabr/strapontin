/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link NomVille}.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class NomVilleTest {

  @Test
  void nettoyer_paysAccole_leRetire() {
    assertEquals("Nantes", NomVille.nettoyer("Nantes, France"));
  }

  @Test
  void nettoyer_deuxCassesDUneMemeVille_donnentLaMemeForme() {
    assertEquals("Tremblay en France", NomVille.nettoyer("Tremblay En France"));
    assertEquals("Tremblay en France", NomVille.nettoyer("Tremblay en France"));
    assertEquals("Tremblay en France", NomVille.nettoyer("TREMBLAY EN FRANCE"));
  }

  @Test
  void nettoyer_cedexEtNumero_sontRetires() {
    assertEquals("Savigny le Temple", NomVille.nettoyer("Savigny le Temple Cedex"));
    assertEquals("Lyon", NomVille.nettoyer("LYON CEDEX 07"));
  }

  @Test
  void nettoyer_tiretsEtApostrophes_gardentLaTypographieFrancaise() {
    assertEquals("Villeneuve-d'Ascq", NomVille.nettoyer("VILLENEUVE-D'ASCQ"));
    assertEquals("Saint-Étienne", NomVille.nettoyer("saint-étienne"));
    assertEquals("L'Haÿ-les-Roses", NomVille.nettoyer("l'haÿ-les-roses"));
    assertEquals("Aix-en-Provence", NomVille.nettoyer("Aix-en-Provence"));
  }

  @Test
  void nettoyer_arrondissement_estConserve() {
    assertEquals("Paris 08", NomVille.nettoyer("Paris 08"));
  }

  @Test
  void nettoyer_absenteOuVide_retourneNull() {
    assertNull(NomVille.nettoyer(null));
    assertNull(NomVille.nettoyer("   "));
    assertNull(NomVille.nettoyer(", France"));
  }
}
