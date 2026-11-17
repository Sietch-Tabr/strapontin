/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.CadenceurAppels;
import fr.strapontin.core.CadenceurAppelsPartage;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.sources.enrichissement.ResolveurNomCanonique;
import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.sources.eventim.ProduitsEcartes;
import fr.strapontin.sources.http.IdentiteHttp;
import fr.strapontin.sources.sites.ClientSiteSalle;
import fr.strapontin.sources.sites.SiteAllmol;
import fr.strapontin.sources.sites.SiteEmbSannois;
import fr.strapontin.sources.sites.SiteLeTriton;
import fr.strapontin.sources.sites.SiteUsineAChapeaux;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.interne.FiltreJetonInterne;
import fr.strapontin.worker.planification.LogAdminJobListener;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
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
   * Lecteur de pages partagé par les sites de salles : un seul rythme (une
   * requête toutes les deux secondes) pour l'ensemble de la lecture quotidienne.
   *
   * @return lecteur au {@code User-Agent} du robot
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ClientSiteSalle clientSiteSalle() {
    return new ClientSiteSalle();
  }

  /**
   * @param client lecteur de pages partagé
   * @return site du Triton (Les Lilas)
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SiteLeTriton siteLeTriton(final ClientSiteSalle client) {
    return new SiteLeTriton(client);
  }

  /**
   * @param client lecteur de pages partagé
   * @return site de l'EMB (Sannois)
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SiteEmbSannois siteEmbSannois(final ClientSiteSalle client) {
    return new SiteEmbSannois(client);
  }

  /**
   * @param client lecteur de pages partagé
   * @return site de L'Usine à Chapeaux (Rambouillet)
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SiteUsineAChapeaux siteUsineAChapeaux(final ClientSiteSalle client) {
    return new SiteUsineAChapeaux(client);
  }

  /**
   * @param client lecteur de pages partagé
   * @return billetterie Allmol Tickets (Antilles, Guyane)
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SiteAllmol siteAllmol(final ClientSiteSalle client) {
    return new SiteAllmol(client);
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
   * @param jdbc accès à la base partagée entre web et worker
   * @return instance dont le cadencement (1 req/s) est partagé avec le module web
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ClientNominatim clientNominatim(
      final NamedParameterJdbcTemplate jdbc, final IdentiteHttp identiteHttp) {
    return new ClientNominatim(
        new CadenceurAppelsPartage(jdbc, "nominatim", ClientNominatim.DELAI_MIN_ENTRE_APPELS_MS),
        identiteHttp);
  }

  /**
   * Identité déclarée aux services externes (version, projet, contact).
   *
   * @param buildProperties version courante
   * @param configService clé {@code CONTACT_EMAIL}
   * @param urlProjet adresse publique du projet
   * @return identité partagée par les appels sortants
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  IdentiteHttp identiteHttp(
      final BuildProperties buildProperties,
      final ConfigServiceWorker configService,
      @Value("${strapontin.projet.url:" + IdentiteHttp.URL_PROJET_PAR_DEFAUT + "}")
          final String urlProjet) {
    return new IdentiteHttp(
        buildProperties.getVersion(),
        urlProjet,
        () -> configService.lireConfig("CONTACT_EMAIL").orElse(""));
  }
}
