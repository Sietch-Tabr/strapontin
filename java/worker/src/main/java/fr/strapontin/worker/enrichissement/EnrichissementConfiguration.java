/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.sources.DeezerArtisteSource;
import fr.strapontin.sources.OverpassSalleSource;
import fr.strapontin.sources.WikidataArtisteSource;
import fr.strapontin.sources.WikidataSalleSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Beans des sources d'enrichissement Wikidata/OSM/Deezer.
///
/// Ajouter une nouvelle source d'enrichissement = créer une implémentation de
/// {@link fr.strapontin.sources.SourceEnrichissementSalle} ou
/// {@link fr.strapontin.sources.SourceEnrichissementArtiste} + un bean ici, sans modifier
/// {@link EnrichissementSallesService} ni {@link EnrichissementArtistesService}.
///
/// @since 2.6.0
/// @author Kwaite
@Configuration
public class EnrichissementConfiguration {

  /// Client Overpass (OSM) pour la découverte initiale des salles par nom.
  ///
  /// @return instance avec RestClient par défaut
  @Bean
  OverpassSalleSource overpassSalleSource() {
    return new OverpassSalleSource();
  }

  /// Client SPARQL Wikidata pour l'enrichissement des salles par Q-ID.
  ///
  /// @return instance avec cache 24h
  @Bean
  WikidataSalleSource wikidataSalleSource() {
    return new WikidataSalleSource();
  }

  /// Client SPARQL Wikidata pour l'enrichissement et la découverte des artistes.
  ///
  /// @return instance avec cache 24h
  @Bean
  WikidataArtisteSource wikidataArtisteSource() {
    return new WikidataArtisteSource();
  }

  /// Client Deezer pour la confirmation du nom canonique d'un artiste par son deezerId.
  ///
  /// @return instance avec RestClient par défaut
  @Bean
  DeezerArtisteSource deezerArtisteSource() {
    return new DeezerArtisteSource();
  }
}
