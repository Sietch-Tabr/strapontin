/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Lieu d'un concert tel que publié par une source.
 *
 * @param salle nom de la salle, obligatoire
 * @param ville ville, peut être null
 * @param dept département, peut être null
 * @param salleSourceId identifiant préfixé du lieu chez la source (ex. {@code "ticketmaster:Z1"}),
 *     peut être null
 * @param position position publiée, {@link PositionLieu#INCONNUE} à défaut
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
