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
 * Libellés source à déplacer, vers une salle existante ({@code cibleId}) ou
 * à créer ({@code nouvelleSalleNom}) : exactement l'une des deux. {@code lieux}
 * : lieux des sources à déplacer ({@code source:identifiant}) ; absent, ceux
 * dont le nom porte un libellé choisi.
 * @since 3.0.2
 * @author Kwaite
 */
public record RequeteRepartitionSalle(
    @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 300) String> libelles,
    @Size(max = 50) List<@NotBlank @Size(max = 400) String> lieux,
    UUID cibleId,
    @Size(max = 200) String nouvelleSalleNom) {

  /**
   * @return true si une seule destination est indiquée
   */
  @AssertTrue(message = "Choisir une salle existante ou le nom d'une salle à créer.")
  public boolean isDestinationUnique() {
    final boolean nouvelle = nouvelleSalleNom != null && !nouvelleSalleNom.isBlank();
    final boolean existante = cibleId != null;
    return existante != nouvelle;
  }
}
