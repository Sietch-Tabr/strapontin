/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/// Corps JSON de la requête POST /api/salles.
///
/// @param nom        nom de la salle
/// @param tmVenueId  identifiant Ticketmaster optionnel
/// @param osmId      identifiant OSM {@code "type/id"} optionnel (#718)
/// @param wikidataId Q-ID Wikidata optionnel (#718)
/// @since 2.1.0
/// @author Kwaite
public record RequeteAjouter(String nom, String tmVenueId, String osmId, String wikidataId) {

  /// Construit une requête sans métadonnées OSM/Wikidata.
  ///
  /// @param nom       nom de la salle
  /// @param tmVenueId identifiant Ticketmaster optionnel
  public RequeteAjouter(String nom, String tmVenueId) {
    this(nom, tmVenueId, null, null);
  }
}
