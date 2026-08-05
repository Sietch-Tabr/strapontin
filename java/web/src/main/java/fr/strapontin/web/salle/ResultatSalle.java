/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/// Résultat de recherche d'une salle ({@code "base"}, {@code "base-digitick"},
/// {@code "ticketmaster"} ou {@code "osm"}).
///
/// @param salle        nom de la salle
/// @param ville        ville (null si inconnue)
/// @param tmVenueId    identifiant Ticketmaster (null si non issu de TM)
/// @param digitickSlug slug Digitick pré-configuré (null si non configuré)
/// @param source       origine du résultat : {@code "base"}, {@code "base-digitick"},
///                     {@code "ticketmaster"}, {@code "osm"}
/// @param osmId identifiant OSM {@code "type/id"} (null si non issu de Nominatim)
/// @param wikidataId Q-ID Wikidata (null si absent des extratags Nominatim)
/// @since 2.1.0
/// @author Kwaite
public record ResultatSalle(
    String salle,
    String ville,
    String tmVenueId,
    String digitickSlug,
    String source,
    String osmId,
    String wikidataId) {

  /// Construit un résultat sans métadonnées OSM/Wikidata (sources historiques).
  ///
  /// @param salle        nom de la salle
  /// @param ville        ville (null si inconnue)
  /// @param tmVenueId    identifiant Ticketmaster (null si non issu de TM)
  /// @param digitickSlug slug Digitick pré-configuré (null si non configuré)
  /// @param source       origine du résultat
  public ResultatSalle(
      String salle, String ville, String tmVenueId, String digitickSlug, String source) {
    this(salle, ville, tmVenueId, digitickSlug, source, null, null);
  }
}
