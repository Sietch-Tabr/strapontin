/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Beans applicatifs : sources de données et résolveur de noms canoniques.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class WorkerConfiguration {

  @Value("${contact.email}")
  private String contactEmail;

  /// Sérialiseur JSON (quota TM dans `app_config`).
  ///
  /// @return instance partagée
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /// Enregistre [LogAdminJobListener] comme listener global sur tous les jobs Quartz.
  ///
  /// @param listener le listener à enregistrer
  /// @return le customizer Spring Boot
  @Bean
  SchedulerFactoryBeanCustomizer globalJobListeners(LogAdminJobListener listener) {
    return factory -> factory.setGlobalJobListeners(listener);
  }

  /// Client Ticketmaster Discovery v2.
  ///
  /// Lit `TICKETMASTER_API_KEY` depuis `app_config` (AES-256-GCM).
  /// Lève une `IllegalStateException` au démarrage si la clé est absente.
  ///
  /// @param configService service de configuration chiffrée
  /// @return instance configurée avec la clé API
  @Bean
  TicketmasterSource ticketmasterSource(ConfigServiceWorker configService) {
    String apiKey =
        configService
            .lireConfig("TICKETMASTER_API_KEY")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "TICKETMASTER_API_KEY absent de app_config — démarrage impossible"));
    return new TicketmasterSource(apiKey);
  }

  /// Client Eventim France.
  ///
  /// @return instance avec User-Agent par défaut
  @Bean
  EventimSource eventimSource() {
    return new EventimSource();
  }

  /// Résolveur de noms canoniques (MusicBrainz + Deezer) avec cache Caffeine 7 jours.
  ///
  /// @return instance configurée avec l'email de contact pour le User-Agent MusicBrainz
  @Bean
  ResolveurNomCanonique resolveurNomCanonique() {
    return new ResolveurNomCanonique(contactEmail);
  }
}
