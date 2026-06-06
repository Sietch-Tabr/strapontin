/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/// Configuration Spring Security 7 pour le mode SPA Vue 3.
///
/// <p>Sessions persistantes JDBC (survivent aux redémarrages). Rate limiting login
/// via {@link RateLimitingLoginFilter} (intercepte {@code /api/auth/login}).
/// Accès direct par IP bloqué via {@link BloquerAccesDirectIpFilter}.
/// CSRF désactivé (sessions {@code SameSite=Strict} + {@code form-action 'self'} en CSP).
/// Login via {@code POST /api/auth/login}, logout via {@code POST /api/auth/logout}.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  /// Registre de sessions persisté en JDBC — survit aux redémarrages.
  ///
  /// @param sessionRepository le dépôt de sessions Spring Session JDBC
  /// @return le registre de sessions
  @Bean
  SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
      FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }

  /// Chaîne de filtres de sécurité principale.
  ///
  /// @param http                le constructeur de configuration Spring Security
  /// @param correlationIdFilter le filtre de corrélation MDC (premier dans la chaîne)
  /// @param rateLimitingFilter  le filtre de rate limiting sur le login
  /// @param bloquerIpFilter     le filtre de blocage d'accès direct par IP
  /// @param totpGuardFilter     le filtre de garde TOTP post-login
  /// @param sessionRegistry     le registre de sessions persisté en JDBC
  /// @param logAdminService     le service de journalisation admin
  /// @return la SecurityFilterChain configurée
  /// @throws Exception en cas d'erreur de configuration
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CorrelationIdFilter correlationIdFilter,
      RateLimitingLoginFilter rateLimitingFilter,
      BloquerAccesDirectIpFilter bloquerIpFilter,
      TotpGuardFilter totpGuardFilter,
      SessionRegistry sessionRegistry,
      LogAdminService logAdminService)
      throws Exception {
    http.headers(
            headers ->
                headers
                    .httpStrictTransportSecurity(
                        hsts -> hsts.maxAgeInSeconds(63072000).includeSubDomains(true))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none';"
                                    + " script-src 'self';"
                                    + " style-src 'self';"
                                    + " img-src 'self' data:;"
                                    + " font-src 'self';"
                                    + " connect-src 'self';"
                                    + " manifest-src 'self';"
                                    + " worker-src 'self';"
                                    + " frame-ancestors 'none';"
                                    + " object-src 'none';"
                                    + " base-uri 'self';"
                                    + " form-action 'self'"))
                    .addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow")))
        .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(bloquerIpFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(totpGuardFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/css/**",
                        "/js/**",
                        "/assets/**",
                        "/fonts/**",
                        "/images/**",
                        "/icons/**",
                        "/favicon.ico",
                        "/manifest.json",
                        "/sw.js",
                        "/offline.html",
                        "/index.html")
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
                    .requestMatchers("/login", "/forgot-password", "/reset-password", "/setup")
                    .permitAll()
                    .requestMatchers("/api/setup")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/resend")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/interne/backup-statut")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/totp")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/client/erreur")
                    .authenticated()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/decouverte/fetcher")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        // CSRF désactivé : sessions SameSite=Strict (identique v1)
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .maximumSessions(10)
                    .sessionRegistry(sessionRegistry))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, _) -> {
                          if (request.getServletPath().startsWith("/api/")) {
                            try {
                              Map<String, Object> meta = new HashMap<>();
                              meta.put("chemin", request.getServletPath());
                              meta.put("ip", request.getRemoteAddr());
                              String ua = request.getHeader("User-Agent");
                              meta.put(
                                  "userAgent",
                                  ua != null ? ua.substring(0, Math.min(ua.length(), 200)) : "");
                              logAdminService.logAdmin(
                                  "warn",
                                  "web",
                                  "Accès API non authentifié : " + request.getServletPath(),
                                  meta);
                            } catch (Exception ignored) {
                            }
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
                            try {
                              Map<String, Object> meta = new HashMap<>();
                              meta.put("chemin", request.getServletPath());
                              meta.put("ip", request.getRemoteAddr());
                              var auth =
                                  org.springframework.security.core.context.SecurityContextHolder
                                      .getContext()
                                      .getAuthentication();
                              if (auth != null) meta.put("userId", auth.getName());
                              logAdminService.logAdmin(
                                  "warn",
                                  "web",
                                  "Accès API refusé : " + request.getServletPath(),
                                  meta);
                            } catch (Exception ignored) {
                            }
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"erreur\":\"Accès refusé\"}");
                          } else {
                            response.sendRedirect("/login");
                          }
                        }));

    return http.build();
  }

  /// Encodeur de mots de passe bcrypt.
  ///
  /// @return l'encodeur de mots de passe
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
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

  /// Force le timeout d'inactivité des sessions JDBC à 30 jours.
  ///
  /// <p>{@code spring.session.timeout=30d} n'est pas appliqué par Spring Session 4 —
  /// ce customizer le remplace de façon programmatique.
  /// La session est prolongée à chaque requête ; elle expire uniquement après
  /// 30 jours sans activité.
  ///
  /// @return le customizer de dépôt de sessions
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTimeoutCustomizer() {
    return repository -> repository.setDefaultMaxInactiveInterval(Duration.ofDays(30));
  }

  /// Publie les événements de session HTTP (requis pour le suivi des sessions actives).
  ///
  /// @return le publisher d'événements de session
  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  /// Sérialiseur de cookies Spring Session — force {@code Secure}, {@code SameSite=Strict}
  /// et {@code max-age} 30 jours.
  ///
  /// <p>Les propriétés {@code server.servlet.session.cookie.*} sont ignorées par Spring Session
  /// JDBC ; ce bean prend le relais.
  ///
  /// @param cookieSecure {@code true} si le flag Secure doit être positionné (désactivé en dev)
  /// @return le sérialiseur de cookies configuré
  @Bean
  CookieSerializer cookieSerializer(@Value("${app.cookie.secure:true}") boolean cookieSecure) {
    DefaultCookieSerializer serialiseur = new DefaultCookieSerializer();
    serialiseur.setUseSecureCookie(cookieSecure);
    serialiseur.setSameSite("Strict");
    serialiseur.setUseHttpOnlyCookie(true);
    serialiseur.setCookieMaxAge(2592000);
    return serialiseur;
  }
}
