/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Lieu d'un concert publié par une source : salle obligatoire, identifiant
 * préfixé chez la source (ex. {@code ticketmaster:Z1}), position publiée.
 * Le nom retenu après dédoublonnage ({@code salle}) peut venir d'une autre source :
 * {@code sallePubliee} garde le nom tel que la source du concert le publie.
 * @param salle nom de la salle retenu pour le concert
 * @param ville ville, peut être null
 * @param dept département, peut être null
 * @param salleSourceId identifiant préfixé du lieu chez la source, peut être null
 * @param position position publiée du lieu
 * @param sallePubliee nom publié par la source du concert, la salle à défaut
 * @since 3.0.2
 * @author Kwaite
 */
public record LieuBrut(
    String salle,
    String ville,
    String dept,
    String salleSourceId,
    PositionLieu position,
    String sallePubliee) {

  /** Valide le nom de salle ; position inconnue et nom publié = salle à défaut. */
  public LieuBrut {
    Objects.requireNonNull(salle, "salle");
    position = position != null ? position : PositionLieu.INCONNUE;
    sallePubliee = sallePubliee != null ? sallePubliee : salle;
  }
}
