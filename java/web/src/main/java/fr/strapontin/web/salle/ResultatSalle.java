/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/**
 * Résultat de recherche d'une salle — {@code source} vaut {@code "base"},
 * {@code "ticketmaster"} ou {@code "osm"} ; tous
 * les champs sauf {@code salle} et {@code source} sont nullables selon
 * l'origine (ex. {@code osmId}/{@code wikidataId} uniquement pour Nominatim).
 * @since 2.1.0
 * @author Kwaite
 */
public record ResultatSalle(
    String salle, String ville, String tmVenueId, String source, String osmId, String wikidataId) {

  /**
   * Construit un résultat sans métadonnées OSM/Wikidata, pour les sources
   * historiques (base, Ticketmaster) — {@code osmId}/{@code
   * wikidataId} restent {@code null}.
   */
  public ResultatSalle(
      final String salle, final String ville, final String tmVenueId, final String source) {
    this(salle, ville, tmVenueId, source, null, null);
  }
}
