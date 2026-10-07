/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ProduitsEcartes;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
   * Authentifie l'API interne /trigger par jeton ({@link FiltreJetonInterne}).
   * @param secret valeur de {@code APP_CONFIG_SECRET}
   * @return l'enregistrement du filtre, limité à {@code /trigger/*}
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  FilterRegistrationBean<FiltreJetonInterne> filtreJetonInterne(
      @Value("${app.config.secret}") final String secret) {
    final FilterRegistrationBean<FiltreJetonInterne> enregistrement =
        new FilterRegistrationBean<>(new FiltreJetonInterne(secret));
    enregistrement.addUrlPatterns("/trigger/*");
    return enregistrement;
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
   * Client Eventim France, interrogé par région sur les villes connues des
   * départements : celles de ses propres lieux déjà observés, complétées des
   * villes des salles connues (tirets remplacés, orthographe Eventim).
   *
   * @param jdbc accès JDBC
   * @return instance avec User-Agent par défaut
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  EventimSource eventimSource(final NamedParameterJdbcTemplate jdbc) {
    final EventimSource source = new EventimSource();
    source.definirFournisseurVilles(
        depts ->
            jdbc.queryForList(
                """
                SELECT DISTINCT ville FROM (
                  SELECT ss.ville_source AS ville FROM salle_sources ss
                  JOIN salles s ON s.id = ss.salle_id
                  WHERE ss.source = 'eventim' AND ss.ville_source IS NOT NULL AND s.dept IN (:depts)
                  UNION
                  SELECT replace(s.ville, '-', ' ') FROM salles s
                  WHERE s.ville IS NOT NULL AND s.dept IN (:depts)
                ) v ORDER BY ville
                """,
                Map.of("depts", depts),
                String.class));
    return source;
  }

  /**
   * @param eventimSource source Eventim
   * @return produits écartés de ses récupérations par artiste
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ProduitsEcartes produitsEcartesEventim(final EventimSource eventimSource) {
    return eventimSource.produitsEcartes();
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
