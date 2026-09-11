/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/artistes.
 *
 * @param nom        nom de l'artiste
 * @param wikidataId Q-ID Wikidata optionnel — fourni quand l'utilisateur sélectionne une
 *                    suggestion Wikidata plutôt que de saisir un nom libre. Format contraint
 * : ce champ est concaténé dans un appel API Wikidata sortant.
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteAjouter(
    @NotBlank(message = "Le nom de l'artiste est obligatoire.")
        @Size(max = 200, message = "Nom trop long (200 caractères max).")
        String nom,
    @Pattern(regexp = "^(Q[0-9]+)?$", message = "Format de Q-ID Wikidata invalide.")
        String wikidataId) {

  /**
   * Construit une requête sans Q-ID Wikidata (saisie libre).
   *
   * @param nom nom de l'artiste
   */
  public RequeteAjouter(String nom) {
    this(nom, null);
  }
}
