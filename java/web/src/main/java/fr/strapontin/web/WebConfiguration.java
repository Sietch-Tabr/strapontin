/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.strapontin.web.securite.AppareilEnregistrementInterceptor;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Configuration générale de l'application web.
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

  private final AppareilEnregistrementInterceptor appareilEnregistrementInterceptor;

  /// @param appareilEnregistrementInterceptor intercepteur d'enregistrement d'appareil
  public WebConfiguration(AppareilEnregistrementInterceptor appareilEnregistrementInterceptor) {
    this.appareilEnregistrementInterceptor = appareilEnregistrementInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(appareilEnregistrementInterceptor).addPathPatterns("/api/**");
  }

  /// ObjectMapper partagé : dates ISO-8601, module java.time.
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  /// RestClient partagé pour les appels HTTP vers des API externes (ex. Ticketmaster).
  ///
  /// <p>Timeout connexion : 5 s — timeout lecture : 10 s.
  @Bean
  RestClient restClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().requestFactory(factory).build();
  }
}
