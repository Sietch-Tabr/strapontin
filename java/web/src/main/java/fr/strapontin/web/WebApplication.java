/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/// Point d'entrée de l'API REST Strapontin.
///
/// <p>Application Spring Boot Web. Expose les endpoints REST consommés par le frontend
/// SvelteKit (et ultérieurement Next.js). Gère l'authentification via Spring Security 7,
/// les sessions persistantes JDBC et la configuration chiffrée.
///
/// @since 2.0.0
/// @author Kwaite
@SpringBootApplication
@EnableCaching
public class WebApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebApplication.class, args);
  }
}
