/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Lieu d'un concert publié par une source : salle obligatoire, identifiant
 * préfixé chez la source (ex. {@code ticketmaster:Z1}), position publiée.
 * @since 3.0.2
 * @author Kwaite
 */
public record LieuBrut(
    String salle, String ville, String dept, String salleSourceId, PositionLieu position) {

  /** Valide le nom de salle ; position inconnue à défaut. */
  public LieuBrut {
    Objects.requireNonNull(salle, "salle");
    position = position != null ? position : PositionLieu.INCONNUE;
  }
}
