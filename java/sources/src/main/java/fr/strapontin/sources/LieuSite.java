/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.Objects;

/**
 * Salle dont le site publie sa programmation : nom, commune, département
 * et code postal à cinq chiffres (null si inconnu).
 * @since 3.0.6
 * @author Kwaite
 */
public record LieuSite(String nom, String ville, String dept, String codePostal) {

  /** Valide le nom de la salle. */
  public LieuSite {
    Objects.requireNonNull(nom, "nom");
  }
}
