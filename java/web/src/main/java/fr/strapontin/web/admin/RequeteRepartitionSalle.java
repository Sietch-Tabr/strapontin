/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Répartition d'une salle : libellés source à déplacer, vers une salle existante ou une salle à
 * créer (exactement l'une des deux).
 *
 * @param libelles libellés source dont les événements changent de salle
 * @param cibleId salle existante qui les reçoit, ou null
 * @param nouvelleSalleNom nom de la salle à créer pour les recevoir, ou null
 * @since 3.0.2
 * @author Kwaite
 */
public record RequeteRepartitionSalle(
    @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 300) String> libelles,
    UUID cibleId,
    @Size(max = 200) String nouvelleSalleNom) {

  /**
   * @return true si une seule destination est indiquée
   */
  @AssertTrue(message = "Choisir une salle existante ou le nom d'une salle à créer.")
  public boolean isDestinationUnique() {
    final boolean nouvelle = nouvelleSalleNom != null && !nouvelleSalleNom.isBlank();
    return (cibleId != null) != nouvelle;
  }
}
