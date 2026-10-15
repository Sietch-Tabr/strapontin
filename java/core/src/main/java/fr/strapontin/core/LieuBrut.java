/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Lieu d'un concert publié par une source : salle obligatoire, identifiant
 * préfixé chez la source, position publiée. Après dédoublonnage {@code
 * salle} peut venir d'une autre source ; {@code sallePubliee} garde le nom
 * publié par la source du concert.
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
