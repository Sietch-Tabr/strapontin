/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests unitaires pour {@link SommesControle}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class SommesControleTest {

  private static final String SOMME_A = "a".repeat(64);
  private static final String SOMME_B = "b".repeat(64);
  private static final String CONTENU = SOMME_A + "  web.jar\n" + SOMME_B + "  worker.jar\n";

  private static Telechargement telechargement(final String nom, final String somme) {
    return new Telechargement(Path.of("/travail", nom), somme, 1);
  }

  @Test
  void verifier_empreinteConforme_estAcceptee() {
    final SommesControle sommes = SommesControle.analyser(CONTENU);
    assertDoesNotThrow(() -> sommes.verifier(telechargement("web.jar", SOMME_A)));
    assertDoesNotThrow(() -> sommes.verifier(telechargement("worker.jar", SOMME_B)));
  }

  @Test
  void verifier_empreinteDifferente_leveUneException() {
    final SommesControle sommes = SommesControle.analyser(CONTENU);
    assertThrows(
        IntegriteException.class, () -> sommes.verifier(telechargement("web.jar", SOMME_B)));
  }

  @Test
  void verifier_fichierAbsentDeLaListe_leveUneException() {
    final SommesControle sommes = SommesControle.analyser(CONTENU);
    assertThrows(
        IntegriteException.class,
        () -> sommes.verifier(telechargement("exploitation.tar.gz", SOMME_A)));
  }

  @Test
  void analyser_accepteLeMarqueurBinaireEtIgnoreLesLignesVides() {
    final SommesControle sommes = SommesControle.analyser("\n" + SOMME_A + " *web.jar\n\n");
    assertDoesNotThrow(() -> sommes.verifier(telechargement("web.jar", SOMME_A)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abc  web.jar",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA  web.jar",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa web.jar extra",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ../web.jar",
        "n'importe quoi"
      })
  void analyser_ligneMalFormee_leveUneException(final String ligne) {
    assertThrows(IntegriteException.class, () -> SommesControle.analyser(ligne));
  }

  @Test
  void analyser_nomEnDouble_leveUneException() {
    final String doublon = SOMME_A + "  web.jar\n" + SOMME_B + "  web.jar\n";
    assertThrows(IntegriteException.class, () -> SommesControle.analyser(doublon));
  }
}
