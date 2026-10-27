/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests des gabarits d'email de sécurité.
 *
 * @since 3.1.1
 * @author Kwaite
 */
class EmailTemplatesSecuriteTest {

  @Test
  void alerteNouvelAppareil_nomEtUserAgent_afficheLeNomLisibleEtLeUserAgentBrutEchappe() {
    final String html =
        EmailTemplatesSecurite.alerteNouvelAppareil(
            "PWA sur iPhone", "Mozilla/5.0 (iPhone) <test>", "https://exemple.fr");

    assertTrue(html.contains(">PWA sur iPhone<"));
    assertTrue(html.contains("Mozilla/5.0 (iPhone) &lt;test&gt;"));
    assertTrue(html.indexOf("PWA sur iPhone") < html.indexOf("Mozilla/5.0"));
  }
}
