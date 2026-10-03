/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.core.JetonInterneWorker;
import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.web.securite.ActiviteUtilisateurInterceptor;
import fr.strapontin.web.securite.AppareilEnregistrementInterceptor;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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

  private static final int PORT_WORKER = 8081;
  private static final Set<String> HOTES_LOCAUX =
      Set.of("localhost", InetAddress.getLoopbackAddress().getHostAddress());

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
   * Horloge système, injectable pour figer le temps en test.
   *
   * @return horloge du fuseau par défaut de la JVM
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Clock horloge() {
    return Clock.systemDefaultZone();
  }

  /**
   * RestClient des API externes (connexion 5 s, lecture 10 s) ; seules les
   * requêtes vers l'API interne du worker portent son jeton.
   * @param secret valeur de {@code APP_CONFIG_SECRET}
   * @return client partagé
   */
  // HttpClient jamais fermé : bean Spring singleton, pool de connexions
  // réutilisé pour toute la durée de vie de l'application.
  @SuppressWarnings("PMD.CloseResource")
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  RestClient restClient(@Value("${app.config.secret}") final String secret) {
    final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(10));
    final String jeton = JetonInterneWorker.calculer(secret);
    return RestClient.builder()
        .requestFactory(factory)
        .requestInterceptor(
            (requete, corps, execution) -> {
              if (estApiInterneWorker(requete.getURI())) {
                requete.getHeaders().set(JetonInterneWorker.ENTETE, jeton);
              }
              return execution.execute(requete, corps);
            })
        .build();
  }

  /* package-private : visibilité élargie pour WebConfigurationTest (même package). */
  static boolean estApiInterneWorker(final URI uri) {
    return uri.getPort() == PORT_WORKER && HOTES_LOCAUX.contains(uri.getHost());
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
