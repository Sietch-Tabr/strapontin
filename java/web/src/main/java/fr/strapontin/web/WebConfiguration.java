/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.web.securite.AppareilEnregistrementInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
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

  /**
   * @param appareilEnregistrementInterceptor intercepteur d'enregistrement
   *     d'appareil
   */
  public WebConfiguration(
      final AppareilEnregistrementInterceptor appareilEnregistrementInterceptor) {
    this.appareilEnregistrementInterceptor = appareilEnregistrementInterceptor;
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(appareilEnregistrementInterceptor).addPathPatterns("/api/**");
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
   * RestClient dédié Digitick — HTTP/1.1 forcé et User-Agent navigateur,
   * comme {@code DigitickSource} (module {@code sources}) : le serveur
   * ferme la connexion en cours de lecture ({@code EOFException}) quand
   * le client négocie HTTP/2 par défaut (incident réel v3.0.0).
   */
  // HttpClient jamais fermé : bean Spring singleton, pool de connexions
  // réutilisé pour toute la durée de vie de l'application.
  @SuppressWarnings("PMD.CloseResource")
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  @Qualifier("digitick")
  RestClient restClientDigitick() {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(15));
    return RestClient.builder()
        .requestFactory(factory)
        .defaultHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .defaultHeader("Accept", "text/html,application/xhtml+xml,*/*")
        .defaultHeader("Accept-Language", "fr-FR,fr;q=0.9")
        .build();
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
