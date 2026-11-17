/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.net.URI;
import java.util.Set;

/**
 * Réglages de la lecture des releases GitHub : API, dépôt, hôtes
 * autorisés, HTTPS exigé (sauf tests), taille maximale et User-Agent.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record ConfigurationSource(
    URI apiBase,
    String depot,
    Set<String> hotesAutorises,
    boolean httpsExige,
    long tailleMax,
    String agent) {

  private static final long TAILLE_MAX_DEFAUT = 150L * 1024 * 1024;

  /** Copie défensive des hôtes autorisés. */
  public ConfigurationSource {
    hotesAutorises = Set.copyOf(hotesAutorises);
  }

  /**
   * Réglages de production : GitHub en HTTPS, fichiers de 150 Mio au plus.
   *
   * @param depot dépôt de la forme {@code propriétaire/nom}
   * @param agent User-Agent à présenter
   * @return les réglages de production
   */
  public static ConfigurationSource github(final String depot, final String agent) {
    return new ConfigurationSource(
        URI.create("https://api.github.com"),
        depot,
        Set.of(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"),
        true,
        TAILLE_MAX_DEFAUT,
        agent);
  }
}
