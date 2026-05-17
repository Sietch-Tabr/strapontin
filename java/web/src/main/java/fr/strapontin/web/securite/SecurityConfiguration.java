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

/// Configuration Spring Security 7 pour les vues Thymeleaf et l'API REST.
///
/// <p>Sessions persistantes JDBC (survivent aux redémarrages). Rate limiting login
/// via {@link RateLimitingLoginFilter} (intercepte {@code /login} et {@code /api/auth/login}).
/// Accès direct par IP bloqué via {@link BloquerAccesDirectIpFilter}.
/// CSRF désactivé (sessions SameSite=Strict). Form login Spring Security sur {@code /login}.
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
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      RateLimitingLoginFilter rateLimitingFilter,
      BloquerAccesDirectIpFilter bloquerIpFilter)
      throws Exception {
    http.addFilterBefore(bloquerIpFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/favicon.ico",
                        "/manifest.json",
                        "/sw.js")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/push")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(
                        "/api/auth/login", "/api/auth/forgot-password", "/api/auth/reset-password")
                    .permitAll()
                    .requestMatchers("/forgot-password", "/reset-password", "/setup")
                    .permitAll()
                    .requestMatchers("/api/setup")
                    .permitAll()
                    .requestMatchers("/api/admin/**", "/admin")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        // CSRF désactivé : sessions SameSite=Strict (identique v1)
        .csrf(AbstractHttpConfigurer::disable)
        // Form login Thymeleaf
        .formLogin(
            form ->
                form.loginPage("/login")
                    .loginProcessingUrl("/login")
                    .usernameParameter("identifiant")
                    .passwordParameter("motDePasse")
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login?erreur=true")
                    .permitAll())
        // Logout
        .logout(
            logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?deconnecte=true")
                    .deleteCookies("SESSION")
                    .permitAll())
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .maximumSessions(10))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, _) -> {
                          // API → 401 JSON ; pages Thymeleaf → redirect /login
                          if (request.getServletPath().startsWith("/api/")) {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"erreur\":\"Authentification requise\"}");
                          } else {
                            response.sendRedirect("/login");
                          }
                        })
                    .accessDeniedHandler(
                        (request, response, _) -> {
                          if (request.getServletPath().startsWith("/api/")) {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"erreur\":\"Accès refusé\"}");
                          } else {
                            response.sendRedirect("/login");
                          }
                        }));

    return http.build();
  }

  /// Encodeur de mots de passe compatible Better-Auth + bcrypt.
  ///
  /// @return l'encodeur de mots de passe
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BetterAuthPasswordEncoder();
  }

  /// Gestionnaire d'authentification avec chargement depuis la base de données.
  ///
  /// @param userDetailsService le service de chargement des utilisateurs
  /// @param passwordEncoder    l'encodeur de mots de passe
  /// @return l'AuthenticationManager configuré
  @Bean
  AuthenticationManager authenticationManager(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  /// Publie les événements de session HTTP (requis pour le suivi des sessions actives).
  ///
  /// @return le publisher d'événements de session
  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }
}
