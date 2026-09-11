/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/**
 * Point d'entrée de l'API REST Strapontin.
 *
 * <p>Application Spring Boot Web. Expose les endpoints REST consommés par le frontend Vue 3 + Vite.
 * Gère l'authentification via Spring Security 7, les sessions persistantes JDBC et la configuration
 * chiffrée.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@SpringBootApplication
@EnableJdbcHttpSession
@EnableScheduling
public class WebApplication {

  /**
   * @param args arguments de la ligne de commande
   */
  public static void main(final String[] args) {
    SpringApplication.run(WebApplication.class, args);
  }
}
