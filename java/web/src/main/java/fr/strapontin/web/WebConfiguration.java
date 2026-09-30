/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.web.securite.ActiviteUtilisateurInterceptor;
import fr.strapontin.web.securite.AppareilEnregistrementInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration générale de l'application web.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

  private final AppareilEnregistrementInterceptor appareilEnregistrementInterceptor;
  private final ActiviteUtilisateurInterceptor activiteUtilisateurInterceptor;

  /**
   * @param appareilEnregistrementInterceptor intercepteur d'enregistrement
   *     d'appareil
   * @param activiteUtilisateurInterceptor suivi de la dernière activité
   *     (référence de la purge d'inactivité)
   */
  public WebConfiguration(
      final AppareilEnregistrementInterceptor appareilEnregistrementInterceptor,
      final ActiviteUtilisateurInterceptor activiteUtilisateurInterceptor) {
    this.appareilEnregistrementInterceptor = appareilEnregistrementInterceptor;
    this.activiteUtilisateurInterceptor = activiteUtilisateurInterceptor;
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(appareilEnregistrementInterceptor).addPathPatterns("/api/**");
    registry.addInterceptor(activiteUtilisateurInterceptor).addPathPatterns("/api/**");
  }

  /**
   * RestClient partagé pour les appels HTTP vers des API externes (ex.
   * Ticketmaster).
   *
   * <p>Timeout connexion : 5 s — timeout lecture : 10 s.
   */
  // HttpClient jamais fermé : bean Spring singleton, pool de connexions
  // réutilisé pour toute la durée de vie de l'application.
  @SuppressWarnings("PMD.CloseResource")
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  RestClient restClient() {
    final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Client Eventim — test d'un lieu saisi à la main par un admin (le fetch
   * lui-même reste l'affaire du worker).
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
   * Cadenceur des appels Ticketmaster partagé avec le worker (fetch programmé)
   * — un verrou en mémoire ne coordonnerait pas deux processus indépendants,
   * voir {@link CadenceurAppelsPartage} .
   *
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
}
