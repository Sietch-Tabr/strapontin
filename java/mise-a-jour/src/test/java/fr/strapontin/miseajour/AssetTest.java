/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests unitaires pour {@link Asset}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class AssetTest {

  private static final URI URL = URI.create("https://github.com/a/b/releases/download/v1/x");

  @Test
  void construction_nomsHabituels_sontAcceptes() {
    assertEquals("web.jar", new Asset("web.jar", URL, 10).nom());
    assertEquals("exploitation.tar.gz", new Asset("exploitation.tar.gz", URL, 0).nom());
    assertEquals("SHA256SUMS", new Asset("SHA256SUMS", URL, 5).nom());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"../web.jar", "a/b.jar", "a\\b.jar", "..", ".caché", "web jar", "a..b"})
  void construction_nomDangereux_leveUneException(final String nom) {
    assertThrows(IllegalArgumentException.class, () -> new Asset(nom, URL, 1));
  }

  @Test
  void construction_urlAbsenteOuTailleNegative_leveUneException() {
    assertThrows(IllegalArgumentException.class, () -> new Asset("web.jar", null, 1));
    assertThrows(IllegalArgumentException.class, () -> new Asset("web.jar", URL, -1));
  }
}
