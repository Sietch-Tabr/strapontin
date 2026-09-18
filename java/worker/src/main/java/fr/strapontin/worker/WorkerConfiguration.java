/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.DigitickSource;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Beans applicatifs : sources de données et résolveur de noms canoniques.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Configuration
public class WorkerConfiguration {

  /** Constructeur par défaut. */
  public WorkerConfiguration() {}

  /**
   * Sérialiseur JSON (quota TM dans {@code app_config}).
   *
   * @return instance partagée
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /**
   * Enregistre [LogAdminJobListener] comme listener global sur tous les jobs
   * Quartz.
   *
   * @param listener le listener à enregistrer
   * @return le customizer Spring Boot
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SchedulerFactoryBeanCustomizer globalJobListeners(final LogAdminJobListener listener) {
    return factory -> factory.setGlobalJobListeners(listener);
  }

  /**
   * Cadenceur des appels Ticketmaster partagé avec le module web —
   * un verrou en mémoire ne coordonnerait pas deux processus
   * indépendants, voir {@link CadenceurAppelsPartage}.
   * @param jdbc accès à la base partagée entre web et worker
   * @return cadenceur adossé à Postgres
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  CadenceurAppels cadenceurTicketmaster(final NamedParameterJdbcTemplate jdbc) {
    return new CadenceurAppelsPartage(
        jdbc, "ticketmaster", TicketmasterSource.DELAI_MIN_ENTRE_APPELS_MS);
  }

  /**
   * Client Ticketmaster Discovery v2 — lit {@code TICKETMASTER_API_KEY}
   * depuis {@code app_config}, lève {@code IllegalStateException} au
   * démarrage si absente.
   * @param configService service de configuration chiffrée
   * @param cadenceurTicketmaster cadenceur partagé inter-processus
   * @return instance configurée avec la clé API
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  TicketmasterSource ticketmasterSource(
      final ConfigServiceWorker configService, final CadenceurAppels cadenceurTicketmaster) {
    final String apiKey =
        configService
            .lireConfig("TICKETMASTER_API_KEY")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "TICKETMASTER_API_KEY absent de app_config — démarrage impossible"));
    return new TicketmasterSource(apiKey, cadenceurTicketmaster);
  }

  /**
   * Client Eventim France.
   *
   * @return instance avec User-Agent par défaut
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  EventimSource eventimSource() {
    return new EventimSource();
  }

  /**
   * Client de scraping Digitick.
   *
   * @return instance avec User-Agent par défaut
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  DigitickSource digitickSource() {
    return new DigitickSource();
  }

  /**
   * Résolveur de noms canoniques (Deezer) avec cache Caffeine 7 jours.
   *
   * @return instance configurée
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ResolveurNomCanonique resolveurNomCanonique() {
    return new ResolveurNomCanonique();
  }

  /**
   * Client de géocodage Nominatim, réservé au dernier recours de résolution de
   * salle (voir {@code persister.ResolveurSalleParConcert}) — jamais sur le
   * chemin courant de résolution.
   *
   * @return instance avec RestClient et cadenceur (1 req/s) par défaut
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ClientNominatim clientNominatim() {
    return new ClientNominatim();
  }
}
