package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.*;

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
    // non-régression #656 : Ø non décomposable par NFD → supprimé sans substitution préalable
    assertEquals("moonspell", Normalisation.normaliserTexte("MØØNSPELL"));
    assertEquals("moonspell", Normalisation.normaliserTexte("møønspell"));
  }

  @Test
  void normaliserTexte_avecLBarree_substitueL() {
    // #656 : Ł (polonais) non décomposable par NFD
    assertEquals("l artiste", Normalisation.normaliserTexte("Ł artiste"));
    assertEquals("lodz", Normalisation.normaliserTexte("ŁÓDŹ"));
  }

  @Test
  void normaliserTexte_avecDBarree_substitueD() {
    // #656 : Đ (serbe/croate) non décomposable par NFD — Đ/đ → d (translittération simple)
    assertEquals("dordevic", Normalisation.normaliserTexte("Đorđević"));
  }

  @Test
  void normaliserTexte_avecNEng_substitueN() {
    // #656 : Ŋ non décomposable par NFD
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
  void normaliserTexte_elyseeAvecEtSansAccents_memeResultat() {
    // non-régression #639 : TM supprime les accents ("Elysee Montmartre") mais la salle existe
    // en base avec accents ("Elysée Montmartre") — la normalisation doit produire la même clé
    assertEquals(
        Normalisation.normaliserTexte("Elysée Montmartre"),
        Normalisation.normaliserTexte("Elysee Montmartre"));
  }

  @Test
  void similarite_chainesIdentiques_retourne1() {
    assertEquals(1.0, Normalisation.similarite("radiohead", "radiohead"));
  }

  @Test
  void similarite_uneChainVide_retourne0() {
    assertEquals(0.0, Normalisation.similarite("", "radiohead"));
    assertEquals(0.0, Normalisation.similarite("radiohead", ""));
  }

  @Test
  void similarite_variationsProches_superieurOuEgalA085() {
    assertTrue(Normalisation.similarite("radiohead", "radioheads") >= 0.85);
    assertTrue(Normalisation.similarite("julien clerc", "julien clere") >= 0.85);
  }

  @Test
  void similarite_artistesDistincts_inferieurA085() {
    assertTrue(Normalisation.similarite("radiohead", "coldplay") < 0.85);
    assertTrue(Normalisation.similarite("beyonce", "adele") < 0.85);
  }

  // --- jaro ---

  @Test
  void jaro_chainesIdentiques_retourne1() {
    assertEquals(1.0, Normalisation.jaro("radiohead", "radiohead"));
  }

  @Test
  void jaro_uneChainVide_retourne0() {
    assertEquals(0.0, Normalisation.jaro("", "radiohead"));
    assertEquals(0.0, Normalisation.jaro("radiohead", ""));
  }

  @Test
  void jaro_krashKarmaVarianteEspaceOmise_superieurOuEgalA095() {
    // #668 : "krash karma"/"krashkarma" — une seule différence (espace), doit passer 0.95
    assertTrue(Normalisation.jaro("krash karma", "krashkarma") >= 0.95);
  }

  @Test
  void jaro_julienDoreVsJulienMorel_inferieurA095() {
    // Deux artistes distincts avec le même préfixe "julien " — ne doivent pas être fusionnés
    assertTrue(Normalisation.jaro("julien dore", "julien morel") < 0.95);
  }

  @Test
  void jaro_artistesDistincts_inferieurA095() {
    assertTrue(Normalisation.jaro("radiohead", "coldplay") < 0.95);
    assertTrue(Normalisation.jaro("mika", "coldplay") < 0.95);
  }
}
