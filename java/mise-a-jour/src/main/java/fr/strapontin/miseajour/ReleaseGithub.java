/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.util.Map;

/**
 * Release GitHub d'une version : étiquette, version, indicateurs de
 * brouillon et de pré-release, fichiers joints par nom.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record ReleaseGithub(
    String etiquette,
    Version version,
    boolean brouillon,
    boolean preRelease,
    Map<String, Asset> assets) {

  /** Copie défensive des fichiers joints. */
  public ReleaseGithub {
    assets = Map.copyOf(assets);
  }
}
