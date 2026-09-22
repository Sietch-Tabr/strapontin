/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ObjectMapper} partagé, extrait de {@link WebConfiguration} — sans
 * dépendance de constructeur, pour ne jamais bloquer sa propre création
 * sur un bean qui a lui-même besoin de {@code ObjectMapper}.
 * @since 3.0.0
 * @author Kwaite
 */
@Configuration
public class JacksonConfiguration {

  /** Constructeur par défaut. */
  public JacksonConfiguration() {}

  /**
   * ObjectMapper partagé : dates ISO-8601, module java.time.
   * @return l'instance partagée
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
