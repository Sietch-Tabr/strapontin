/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Pages réelles des sites de salles, relevées le 17/10/2026 puis réduites aux premières fiches. */
final class FixturesSites {

  private FixturesSites() {}

  static String lire(final String nom) {
    try (InputStream flux = FixturesSites.class.getResourceAsStream("/sites/" + nom)) {
      return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
