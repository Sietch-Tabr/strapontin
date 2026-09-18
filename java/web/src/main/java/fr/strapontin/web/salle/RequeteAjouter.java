/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/salles — {@code nom} obligatoire,
 * {@code tmVenueId}/{@code osmId}/{@code wikidataId} optionnels ;
 * {@code wikidataId} est concaténé dans une requête SPARQL par
 * {@code WikidataSalleSource}, d'où son format contraint.
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteAjouter(
    @NotBlank(message = "Le nom de la salle est obligatoire.")
        @Size(max = 200, message = "Nom trop long (200 caractères max).")
        String nom,
    String tmVenueId,
    String osmId,
    @Pattern(regexp = "^(Q[0-9]+)?$", message = "Format de Q-ID Wikidata invalide.")
        String wikidataId) {

  /**
   * Construit une requête sans métadonnées OSM/Wikidata.
   *
   * @param nom nom de la salle
   * @param tmVenueId identifiant Ticketmaster optionnel
   */
  public RequeteAjouter(final String nom, final String tmVenueId) {
    this(nom, tmVenueId, null, null);
  }
}
