/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.exploitation.Version;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link IdentiteAttendue}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class IdentiteAttendueTest {

  @Test
  void pourRelease_vise_leWorkflowCiSurLeTagDeLaVersion() {
    final IdentiteAttendue identite =
        IdentiteAttendue.pourRelease("Sietch-Tabr/strapontin", Version.analyser("3.2.0"));
    assertEquals(
        "https://github.com/Sietch-Tabr/strapontin/.github/workflows/ci.yml@refs/tags/v3.2.0",
        identite.sujetCertificat());
    assertEquals("refs/tags/v3.2.0", identite.reference());
    assertEquals("https://github.com/Sietch-Tabr/strapontin", identite.adresseDepot());
  }

  @Test
  void pourRelease_preVersion_garideLeSuffixeDansLaReference() {
    final IdentiteAttendue identite =
        IdentiteAttendue.pourRelease("a/b", Version.analyser("3.2.0-dev.1"));
    assertEquals("refs/tags/v3.2.0-dev.1", identite.reference());
  }
}
