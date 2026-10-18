/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests de {@link LibelleAppareil} sur des en-têtes {@code User-Agent} réels de navigateurs.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class LibelleAppareilTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36|Chrome sur Android",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1|Safari sur iOS",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0|Firefox sur Windows",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0|Edge sur Windows",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15|Safari sur macOS",
        "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0|Firefox sur Linux",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.0.0 Mobile/15E148 Safari/604.1|Chrome sur iOS"
      })
  void depuisUserAgent_navigateursCourants_nommeNavigateurEtSysteme(
      final String userAgent, final String attendu) {
    assertEquals(attendu, LibelleAppareil.depuisUserAgent(userAgent));
  }

  @Test
  void depuisUserAgent_absentOuIllisible_retourneNull() {
    assertNull(LibelleAppareil.depuisUserAgent(null));
    assertNull(LibelleAppareil.depuisUserAgent("   "));
    assertNull(LibelleAppareil.depuisUserAgent("curl/8.4"));
  }
}
