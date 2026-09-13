/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Beans d'infrastructure d'authentification et de session (encodeur de mot de passe, {@code
 * AuthenticationManager}, timeout/sérialisation de session JDBC).
 *
 * <p>Extrait de {@link SecurityConfiguration} pour rester sous le seuil de couplage entre objets
 * (20).
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Configuration
public class SessionSecurityConfiguration {

  /** Constructeur par défaut. */
  public SessionSecurityConfiguration() {}

  /**
   * Encodeur de mots de passe bcrypt.
   *
   * @return l'encodeur de mots de passe
   */
  /* package-private : Bean Spring, jamais appelé directement hors du conteneur. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Gestionnaire d'authentification avec chargement depuis la base de données.
   *
   * <p>Attache un {@link org.springframework.security.authentication.AuthenticationEventPublisher}
   * explicitement : un {@link ProviderManager} construit à la main, hors du chemin
   * d'auto-configuration Spring Boot standard, n'en reçoit aucun par défaut — sans lui, {@code
   * AuthenticationSuccessEvent}/ {@code AbstractAuthenticationFailureEvent} ne sont jamais publiés
   * et {@link AuthEvenementListener} reste du code mort.
   *
   * @param userDetailsService le service de chargement des utilisateurs
   * @param passwordEncoder l'encodeur de mots de passe
   * @param eventPublisher l'éditeur d'événements applicatif Spring
   * @return l'AuthenticationManager configuré
   */
  /* package-private : Bean Spring, jamais appelé directement hors du conteneur. */
  @Bean
  AuthenticationManager authenticationManager(
      final UserDetailsService userDetailsService,
      final PasswordEncoder passwordEncoder,
      final ApplicationEventPublisher eventPublisher) {
    final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    final ProviderManager providerManager = new ProviderManager(provider);
    providerManager.setAuthenticationEventPublisher(
        new DefaultAuthenticationEventPublisher(eventPublisher));
    return providerManager;
  }

  /**
   * Force le timeout d'inactivité des sessions JDBC à 30 jours.
   *
   * <p>{@code spring.session.timeout=30d} n'est pas appliqué par Spring Session 4 — ce customizer
   * le remplace de façon programmatique. La session est prolongée à chaque requête ; elle expire
   * uniquement après 30 jours sans activité.
   *
   * @return le customizer de dépôt de sessions
   */
  /* package-private : Bean Spring, jamais appelé directement hors du conteneur. */
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTimeoutCustomizer() {
    return repository -> repository.setDefaultMaxInactiveInterval(Duration.ofDays(30));
  }

  /**
   * Publie les événements de session HTTP (requis pour le suivi des sessions actives).
   *
   * @return le publisher d'événements de session
   */
  /* package-private : Bean Spring, jamais appelé directement hors du conteneur. */
  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  /**
   * Sérialiseur de cookies Spring Session — force {@code Secure}, {@code SameSite=Lax} et {@code
   * max-age} 30 jours.
   *
   * <p>Les propriétés {@code server.servlet.session.cookie.*} sont ignorées par Spring Session JDBC
   * ; ce bean prend le relais.
   *
   * <p>{@code SameSite=Lax} (et non Strict) : Safari applique Strict plus strictement que Chrome et
   * bloque le cookie sur toute navigation entrante (lien mail, barre d'adresse depuis un autre
   * site, retour d'une autre app iOS). Lax autorise le cookie sur les navigations GET entrantes
   * tout en le bloquant sur les requêtes cross-site non-GET — protection CSRF équivalente.
   *
   * @param cookieSecure {@code true} si le flag Secure doit être positionné (désactivé en dev)
   * @return le sérialiseur de cookies configuré
   */
  /* package-private : Bean Spring, jamais appelé directement hors du conteneur. */
  @Bean
  CookieSerializer cookieSerializer(
      @Value("${app.cookie.secure:true}") final boolean cookieSecure) {
    final DefaultCookieSerializer serialiseur = new DefaultCookieSerializer();
    serialiseur.setUseSecureCookie(cookieSecure);
    serialiseur.setSameSite("Lax");
    serialiseur.setUseHttpOnlyCookie(true);
    serialiseur.setCookieMaxAge(2_592_000);
    return serialiseur;
  }
}
