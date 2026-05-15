/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/// Configuration Spring Security 7 pour l'API REST Strapontin.
///
/// <p>Sessions persistantes JDBC (survivent aux redémarrages). Rate limiting login
/// via {@link RateLimitingLoginFilter}. Accès direct par IP bloqué via
/// {@link BloquerAccesDirectIpFilter}. CSRF désactivé (API REST + SameSite=Strict).
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  /// Chaîne de filtres de sécurité principale.
  ///
  /// @param http               le constructeur de configuration Spring Security
  /// @param rateLimitingFilter le filtre de rate limiting sur le login
  /// @param bloquerIpFilter    le filtre de blocage d'accès direct par IP
  /// @return la SecurityFilterChain configurée
  /// @throws Exception en cas d'erreur de configuration
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      RateLimitingLoginFilter rateLimitingFilter,
      BloquerAccesDirectIpFilter bloquerIpFilter)
      throws Exception {
    http.addFilterBefore(bloquerIpFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/push")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/auth/login")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        // CSRF désactivé : API REST avec sessions SameSite=Strict
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .maximumSessions(10))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, _) -> {
                          response.setStatus(401);
                          response.setContentType("application/json;charset=UTF-8");
                          response.getWriter().write("{\"erreur\":\"Authentification requise\"}");
                        })
                    .accessDeniedHandler(
                        (request, response, _) -> {
                          response.setStatus(403);
                          response.setContentType("application/json;charset=UTF-8");
                          response.getWriter().write("{\"erreur\":\"Accès refusé\"}");
                        }));

    return http.build();
  }

  /// Encodeur de mots de passe compatible Better-Auth + bcrypt.
  ///
  /// @return l'encodeur de mots de passe
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BetterAuthPasswordEncoder();
  }

  /// Gestionnaire d'authentification avec chargement depuis la base de données.
  ///
  /// @param userDetailsService le service de chargement des utilisateurs
  /// @param passwordEncoder    l'encodeur de mots de passe
  /// @return l'AuthenticationManager configuré
  @Bean
  public AuthenticationManager authenticationManager(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  /// Publie les événements de session HTTP (requis pour le suivi des sessions actives).
  ///
  /// @return le publisher d'événements de session
  @Bean
  public HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }
}
