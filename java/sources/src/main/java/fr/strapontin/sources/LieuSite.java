/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.Objects;

/**
 * Salle dont le site publie sa propre programmation.
 * @param nom nom de la salle
 * @param ville commune
 * @param dept code du département
 * @param codePostal code postal à cinq chiffres, null si inconnu
 * @since 3.0.6
 * @author Kwaite
 */
public record LieuSite(String nom, String ville, String dept, String codePostal) {

  /** Valide le nom de la salle. */
  public LieuSite {
    Objects.requireNonNull(nom, "nom");
  }
}
