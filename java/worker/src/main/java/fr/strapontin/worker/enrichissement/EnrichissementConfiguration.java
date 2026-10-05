/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.DeezerArtisteSource;
import fr.strapontin.sources.NominatimSalleSource;
import fr.strapontin.sources.WikidataArtisteSource;
import fr.strapontin.sources.WikidataSalleSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans des sources d'enrichissement Wikidata/OSM/Deezer —
 * ajouter une source = une implémentation + un bean ici, sans
 * modifier les services d'orchestration.
 * @since 2.6.0
 * @author Kwaite
 */
@Configuration
public class EnrichissementConfiguration {

  /** Constructeur par défaut. */
  public EnrichissementConfiguration() {}

  /**
   * Identifiant Wikidata d'une salle lu sur l'objet OpenStreetMap trouvé par
   * Nominatim autour de sa position ; déclaré avant {@link
   * #wikidataSalleSource()}, qui reprend le Q-ID découvert.
   *
   * @param clientNominatim client partagé, cadencé à une requête par seconde
   * @return instance
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  NominatimSalleSource nominatimSalleSource(final ClientNominatim clientNominatim) {
    return new NominatimSalleSource(clientNominatim);
  }

  /**
   * Client SPARQL Wikidata pour l'enrichissement des salles par Q-ID.
   *
   * @return instance avec cache 24h
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  WikidataSalleSource wikidataSalleSource() {
    return new WikidataSalleSource();
  }

  /**
   * Client SPARQL Wikidata pour l'enrichissement et la découverte des artistes.
   *
   * @return instance avec cache 24h
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  WikidataArtisteSource wikidataArtisteSource() {
    return new WikidataArtisteSource();
  }

  /**
   * Client Deezer pour la confirmation du nom canonique d'un artiste par son
   * deezerId.
   *
   * @return instance avec RestClient par défaut
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  DeezerArtisteSource deezerArtisteSource() {
    return new DeezerArtisteSource();
  }
}
