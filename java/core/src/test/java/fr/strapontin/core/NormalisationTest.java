/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NormalisationTest {

  @Test
  void normaliserTexte_majuscules_retourneMinuscules() {
    assertEquals("radiohead", Normalisation.normaliserTexte("RADIOHEAD"));
  }

  @Test
  void normaliserTexte_avecAccents_supprimeAccents() {
    assertEquals("julien dore", Normalisation.normaliserTexte("Julien Doré"));
    assertEquals("helena", Normalisation.normaliserTexte("Héléna"));
  }

  @Test
  void normaliserTexte_avecApostrophes_remplaceeParEspace() {
    assertEquals("n golo", Normalisation.normaliserTexte("N'Golo"));
    assertEquals("l artiste", Normalisation.normaliserTexte("l'artiste"));
  }

  @Test
  void normaliserTexte_avecTirets_remplacesParEspace() {
    assertEquals("helene martin", Normalisation.normaliserTexte("Helene-Martin"));
    assertEquals("test valeur", Normalisation.normaliserTexte("test—valeur"));
    assertEquals("test valeur", Normalisation.normaliserTexte("test–valeur"));
  }

  @Test
  void normaliserTexte_avecCaracteresSpeciaux_remplacesParEspace() {
    assertEquals("ac dc", Normalisation.normaliserTexte("AC/DC"));
    assertEquals("foo bar", Normalisation.normaliserTexte("foo.bar"));
  }

  @Test
  void normaliserTexte_avecOSlash_substitueO() {
    // non-régression : Ø non décomposable par NFD → supprimé sans substitution préalable
    assertEquals("moonspell", Normalisation.normaliserTexte("MØØNSPELL"));
    assertEquals("moonspell", Normalisation.normaliserTexte("møønspell"));
  }

  @Test
  void normaliserTexte_avecLBarree_substitueL() {
    // Ł (polonais) non décomposable par NFD
    assertEquals("l artiste", Normalisation.normaliserTexte("Ł artiste"));
    assertEquals("lodz", Normalisation.normaliserTexte("ŁÓDŹ"));
  }

  @Test
  void normaliserTexte_avecDBarree_substitueD() {
    // Đ (serbe/croate) non décomposable par NFD — Đ/đ → d (translittération simple)
    assertEquals("dordevic", Normalisation.normaliserTexte("Đorđević"));
  }

  @Test
  void normaliserTexte_avecNEng_substitueN() {
    // Ŋ non décomposable par NFD
    assertEquals("nanga", Normalisation.normaliserTexte("Ŋanga"));
  }

  @Test
  void normaliserTexte_avecEspacesMultiples_normalises() {
    assertEquals("foo bar", Normalisation.normaliserTexte("  foo   bar  "));
  }

  @Test
  void normaliserTexte_chaineVide_retourneVide() {
    assertEquals("", Normalisation.normaliserTexte(""));
  }

  @Test
  void normaliserTexte_avecChiffres_conserveChiffres() {
    assertEquals("blink 182", Normalisation.normaliserTexte("Blink-182"));
  }

  @Test
  void normaliserVille_saintAvecTiret_abregeSt() {
    assertEquals("st denis", Normalisation.normaliserVille("Saint-Denis"));
    assertEquals("st denis", Normalisation.normaliserVille("Saint Denis"));
  }

  @Test
  void normaliserVille_sainteAvecTiret_abregeSte() {
    assertEquals("ste foy", Normalisation.normaliserVille("Sainte-Foy"));
  }

  @Test
  void normaliserVille_sansSaint_inchangee() {
    assertEquals("paris", Normalisation.normaliserVille("Paris"));
    assertEquals("lyon", Normalisation.normaliserVille("Lyon"));
  }

  @Test
  void normaliserVille_avecAccents_normalise() {
    assertEquals("ile de france", Normalisation.normaliserVille("Île-de-France"));
  }

  @Test
  void normaliserTexte_dollarStyleArtiste_supprimeSansEspace() {
    // TM utilise "$" comme "S" — supprimé sans espace pour éviter "chri tian"
    // ("chri$tian" → "chritian", pas "christian") mais Jaro("chritian", "christian") ≥ 0.95
    assertEquals("chritian", Normalisation.normaliserTexte("Chri$tian"));
    assertEquals("chritian gate", Normalisation.normaliserTexte("Chri$tian Gate$"));
  }

  @Test
  void normaliserTexte_esperluetteIsolee_normaliseEnAnd() {
    // "Katia & Marielle" (fetch 1) et "Katia and Marielle" (fetch 2) → même norm
    assertEquals("katia and marielle", Normalisation.normaliserTexte("Katia & Marielle"));
    assertEquals("katia and marielle", Normalisation.normaliserTexte("Katia and Marielle"));
  }

  @Test
  void normaliserTexte_esperluetteColleeMotsSansEspace_remplaceeParEspace() {
    // Esperluette sans espaces → remplacée par espace via NON_ALPHANUM (pas "and")
    assertEquals("rock roll", Normalisation.normaliserTexte("rock&roll"));
  }

  @Test
  void normaliserTexte_elyseeAvecEtSansAccents_memeResultat() {
    // non-régression : TM supprime les accents ("Elysee Montmartre") mais la salle existe
    // en base avec accents ("Elysée Montmartre") — la normalisation doit produire la même clé
    assertEquals(
        Normalisation.normaliserTexte("Elysée Montmartre"),
        Normalisation.normaliserTexte("Elysee Montmartre"));
  }

  // --- extraireDeptFrancais (ex-TicketmasterSource/EventimSource.extraireDept) ---

  @Test
  void extraireDeptFrancais_codePostalAbsent_retourneNull() {
    assertNull(Normalisation.extraireDeptFrancais(null));
    assertNull(Normalisation.extraireDeptFrancais(""));
    assertNull(Normalisation.extraireDeptFrancais("   "));
  }

  @Test
  void extraireDeptFrancais_metropole_retourneDeuxChiffres() {
    assertEquals("75", Normalisation.extraireDeptFrancais("75012"));
    assertEquals("69", Normalisation.extraireDeptFrancais("69001"));
    assertEquals("13", Normalisation.extraireDeptFrancais("13001"));
    assertEquals("01", Normalisation.extraireDeptFrancais("01000"));
  }

  @Test
  void extraireDeptFrancais_DOM_retourneTroisChiffres() {
    assertEquals("971", Normalisation.extraireDeptFrancais("97100"));
    assertEquals("972", Normalisation.extraireDeptFrancais("97200"));
    assertEquals("976", Normalisation.extraireDeptFrancais("97600"));
  }

  @Test
  void extraireDeptFrancais_codePostalCourt_retourneChaine() {
    assertEquals("75", Normalisation.extraireDeptFrancais("75"));
  }

  @Test
  void extraireDeptFrancais_avecEspacesSuperflus_ignoreLesEspaces() {
    assertEquals("75", Normalisation.extraireDeptFrancais("  75012  "));
  }

  // --- normaliserCasse (ex-TicketmasterSource.normaliserCasse) ---

  @Test
  void normaliserCasse_nomToutMajuscule_retourneTitleCase() {
    assertEquals("Accor Arena", Normalisation.normaliserCasse("ACCOR ARENA"));
  }

  @Test
  void normaliserCasse_nomAvecCasseMixte_retourneInchange() {
    assertEquals("Accor Arena", Normalisation.normaliserCasse("Accor Arena"));
  }

  @Test
  void normaliserCasse_nomAvecApostrophe_capitaliseApresApostrophe() {
    assertEquals("L'Olympia", Normalisation.normaliserCasse("L'OLYMPIA"));
  }

  @Test
  void normaliserCasse_articleEnMinuscule_conserveMinuscule() {
    assertEquals("Orchestre de Paris", Normalisation.normaliserCasse("ORCHESTRE DE PARIS"));
  }
}
