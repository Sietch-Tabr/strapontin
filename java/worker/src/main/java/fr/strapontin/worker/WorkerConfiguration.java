/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Beans applicatifs : sources de données et résolveur de noms canoniques.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class WorkerConfiguration {

  @Value("${ticketmaster.api-key}")
  private String tmApiKey;

  @Value("${contact.email}")
  private String contactEmail;

  /// Sérialiseur JSON (quota TM dans {@code app_config}).
  ///
  /// @return instance partagée
  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /// Client Ticketmaster Discovery v2.
  ///
  /// @return instance configurée avec la clé API
  @Bean
  public TicketmasterSource ticketmasterSource() {
    return new TicketmasterSource(tmApiKey);
  }

  /// Client Eventim France.
  ///
  /// @return instance avec User-Agent par défaut
  @Bean
  public EventimSource eventimSource() {
    return new EventimSource();
  }

  /// Résolveur de noms canoniques (MusicBrainz + Deezer) avec cache Caffeine 7 jours.
  ///
  /// @return instance configurée avec l'email de contact pour le User-Agent MusicBrainz
  @Bean
  public ResolveurNomCanonique resolveurNomCanonique() {
    return new ResolveurNomCanonique(contactEmail);
  }
}
