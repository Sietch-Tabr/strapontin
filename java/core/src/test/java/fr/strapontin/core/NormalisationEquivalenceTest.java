/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * La normalisation optimisée donne la même sortie que l'implémentation de référence, sur des noms
 * réels (artistes, salles, villes de la copie de production du dev) et sur les cas limites.
 *
 * @author Kwaite
 */
class NormalisationEquivalenceTest {

  private static final Path CORPUS = Path.of("src/test/resources/normalisation/noms-reels.txt");

  private static final List<String> CAS_LIMITES =
      List.of(
          "",
          " ",
          "   ",
          "a",
          "A",
          "déjà vu",
          "Saint-Étienne",
          "SAINTE-MARIE",
          "saint saint sainte",
          "Katia & Marielle",
          "Katia &  Marielle",
          "a & & b",
          "a&b",
          "a -&- b",
          "a ’&’ b",
          "Chri$tian",
          "$$$",
          "Bjørk Łukasz Đorđe Ŋ",
          "BJØRK ŁUKASZ",
          "L'Olympia – Paris — 18e",
          "tab\tet\nsaut",
          "nbsp insécable",
          "  espaces   multiples  ",
          "ça c'est l'été !",
          "naïve café résumé",
          "N°1 (live) [2026]",
          "日本語のタイトル",
          "emoji 😀 test",
          "ß straße",
          "İstanbul",
          "ﬁligrane",
          "O.K. Computer",
          "abc123",
          "abc 123",
          "a  b",
          " a",
          "a ");

  private static List<String> entrees() throws IOException {
    final List<String> noms = new ArrayList<>(Files.readAllLines(CORPUS, StandardCharsets.UTF_8));
    noms.addAll(CAS_LIMITES);
    return noms;
  }

  @Test
  void normaliserTexte_corpusEtCasLimites_memeSortieQueLaReference() throws IOException {
    for (final String entree : entrees()) {
      assertThat(Normalisation.normaliserTexte(entree))
          .as("normaliserTexte(%s)", entree)
          .isEqualTo(NormalisationReference.normaliserTexte(entree));
    }
  }

  @Test
  void normaliserVille_corpusEtCasLimites_memeSortieQueLaReference() throws IOException {
    for (final String entree : entrees()) {
      assertThat(Normalisation.normaliserVille(entree))
          .as("normaliserVille(%s)", entree)
          .isEqualTo(NormalisationReference.normaliserVille(entree));
    }
  }

  @Test
  void normaliserSalleCle_corpusEtCasLimites_memeSortieQueLaReference() throws IOException {
    for (final String entree : entrees()) {
      assertThat(Normalisation.normaliserSalleCle(entree))
          .as("normaliserSalleCle(%s)", entree)
          .isEqualTo(NormalisationReference.normaliserSalleCle(entree));
    }
  }

  @Test
  void corpus_estAssezLargePourComparerLesTroisFonctions() throws IOException {
    assertThat(entrees()).hasSizeGreaterThan(1000);
  }
}
