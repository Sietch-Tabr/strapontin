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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
 * Beans d'infrastructure d'authentification/session (encodeur,
 * AuthenticationManager, timeout/sérialisation de session JDBC).
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
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Gestionnaire d'authentification sur la base (CLAUDE.md, Authentification)
   * ; refuse un compte dont un nouveau mot de passe est exigé.
   * @param jdbc accès JDBC ({@link VerificationMotDePasseExige})
   * @return l'AuthenticationManager configuré
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  AuthenticationManager authenticationManager(
      final UserDetailsService userDetailsService,
      final PasswordEncoder passwordEncoder,
      final ApplicationEventPublisher eventPublisher,
      final NamedParameterJdbcTemplate jdbc) {
    final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    provider.setPostAuthenticationChecks(new VerificationMotDePasseExige(jdbc));
    final ProviderManager providerManager = new ProviderManager(provider);
    providerManager.setAuthenticationEventPublisher(
        new DefaultAuthenticationEventPublisher(eventPublisher));
    return providerManager;
  }

  /**
   * Force le timeout d'inactivité des sessions JDBC à 30 jours
   * (détail : CLAUDE.md, Authentification).
   * @return le customizer de dépôt de sessions
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTimeoutCustomizer() {
    return repository -> repository.setDefaultMaxInactiveInterval(Duration.ofDays(30));
  }

  /**
   * Publie les événements de session HTTP (requis pour le suivi des sessions
   * actives).
   *
   * @return le publisher d'événements de session
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  /**
   * Sérialiseur de cookies Spring Session — force Secure,
   * SameSite=Lax et max-age 30 jours (détail : CLAUDE.md,
   * Authentification).
   * @param cookieSecure {@code true} si Secure doit être positionné
   * @return le sérialiseur de cookies configuré
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
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
