/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests de {@link NomAppareil} sur des en-têtes {@code User-Agent} réels de navigateurs.
 *
 * @since 3.1.1
 * @author Kwaite
 */
class NomAppareilTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/130.0.0.0 Mobile Safari/537.36|Chrome sur Android",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
            + " Gecko) Version/18.0 Mobile/15E148 Safari/604.1|Safari sur iPhone",
        "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko)"
            + " Version/18.0 Mobile/15E148 Safari/604.1|Safari sur iPad",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0|Firefox"
            + " sur Windows",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0|Edge sur Windows",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)"
            + " Version/17.5 Safari/605.1.15|Safari sur macOS",
        "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0|Firefox sur Linux",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
            + " Gecko) CriOS/130.0.0.0 Mobile/15E148 Safari/604.1|Chrome sur iPhone"
      })
  void libelle_navigateursCourants_nommeNavigateurEtSysteme(
      final String userAgent, final String attendu) {
    assertEquals(attendu, NomAppareil.depuis(userAgent, "navigateur").libelle());
  }

  @Test
  void libelle_modePwa_nommeLApplicationEtLeSysteme() {
    final String safariIphone =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
            + " Gecko) Mobile/15E148";

    assertEquals("PWA sur iPhone", NomAppareil.depuis(safariIphone, "pwa").libelle());
  }

  @Test
  void libelle_absentOuIllisible_retourneAppareilInconnu() {
    assertEquals("Appareil inconnu", NomAppareil.depuis(null, null).libelle());
    assertEquals("Appareil inconnu", NomAppareil.depuis("   ", "navigateur").libelle());
    assertEquals("Appareil inconnu", NomAppareil.depuis("curl/8.4", "valeur-folle").libelle());
  }

  @Test
  void memeAppareil_versionDuNavigateurDifferente_retourneTrue() {
    final NomAppareil ancien =
        NomAppareil.depuis("Mozilla/5.0 (Windows NT 10.0) Chrome/129.0.0.0 Safari/537.36", null);
    final NomAppareil recent =
        NomAppareil.depuis("Mozilla/5.0 (Windows NT 10.0) Chrome/131.0.0.0 Safari/537.36", null);

    assertTrue(recent.memeAppareil(ancien));
  }

  @Test
  void memeAppareil_modesConnusDifferents_retourneFalse() {
    final String ua = "Mozilla/5.0 (Windows NT 10.0) Chrome/131.0.0.0 Safari/537.36";

    assertFalse(NomAppareil.depuis(ua, "pwa").memeAppareil(NomAppareil.depuis(ua, "navigateur")));
    assertTrue(NomAppareil.depuis(ua, "pwa").memeAppareil(NomAppareil.depuis(ua, null)));
  }

  @Test
  void memeAppareil_systemeDifferent_retourneFalse() {
    final NomAppareil windows =
        NomAppareil.depuis("Mozilla/5.0 (Windows NT 10.0) Chrome/131.0.0.0 Safari/537.36", null);
    final NomAppareil android =
        NomAppareil.depuis("Mozilla/5.0 (Linux; Android 14) Chrome/131.0.0.0 Safari/537.36", null);

    assertFalse(windows.memeAppareil(android));
  }
}
