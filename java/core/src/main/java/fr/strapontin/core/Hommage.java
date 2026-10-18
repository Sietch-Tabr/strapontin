/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;
import java.util.Set;

/**
 * Reconnaît l'intitulé d'un concert d'hommage (« tribute ») : un groupe
 * qui reprend le répertoire d'un artiste n'est pas cet artiste, et ne
 * doit ni lui être rattaché ni notifier ses abonnés.
 * @since 3.0.6
 * @author Kwaite
 */
public final class Hommage {

  private static final Set<String> MOTS_HOMMAGE = Set.of("tribute", "hommage");

  private Hommage() {}

  /**
   * @param intitule intitulé d'affiche ou nom d'artiste, brut ou normalisé
   * @return {@code true} si l'intitulé annonce un hommage à un autre artiste
   */
  public static boolean estHommage(final String intitule) {
    Objects.requireNonNull(intitule, "intitule");
    for (final String mot : Normalisation.normaliserTexte(intitule).split(" ")) {
      if (MOTS_HOMMAGE.contains(mot)) {
        return true;
      }
    }
    return false;
  }
}
