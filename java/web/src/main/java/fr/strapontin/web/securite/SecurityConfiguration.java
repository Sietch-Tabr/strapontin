/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * Configuration Spring Security 7 pour le mode SPA Vue 3
 * (détail : CLAUDE.md, Authentification).
 * @since 2.0.0
 * @author Kwaite
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  private final ConfigService configService;

  /**
   * @param configService origine publique ({@link ProtectionCsrfFilter})
   */
  public SecurityConfiguration(final ConfigService configService) {
    this.configService = configService;
  }

  /**
   * Registre de sessions persisté en JDBC — survit aux redémarrages.
   *
   * @param sessionRepository le dépôt de sessions Spring Session JDBC
   * @return le registre de sessions
   */
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }

  /**
   * Chaîne de filtres de sécurité principale (paramètres
   * auto-descriptifs, injectés par Spring).
   * @return la SecurityFilterChain configurée
   * @throws Exception en cas d'erreur de configuration
   */
  // throws Exception imposé par HttpSecurity.build() (SecurityBuilder<O>), pas
  // modifiable.
  @Bean
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  SecurityFilterChain securityFilterChain(
      final HttpSecurity http,
      final CorrelationIdFilter correlationIdFilter,
      final RateLimitingLoginFilter rateLimitingFilter,
      final BloquerAccesDirectIpFilter bloquerIpFilter,
      final TotpGuardFilter totpGuardFilter,
      final SessionRegistry sessionRegistry,
      final LogAdminService logAdminService)
      throws Exception {
    http.headers(this::configurerHeaders)
        .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(bloquerIpFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(totpGuardFilter, UsernamePasswordAuthenticationFilter.class)
        // Après l'autorisation : un appel non authentifié reste un 401, pas un 403.
        .addFilterAfter(new ProtectionCsrfFilter(configService), AuthorizationFilter.class)
        .authorizeHttpRequests(this::configurerAutorisations)
        // Jeton CSRF de Spring désactivé : protection assurée par ProtectionCsrfFilter
        // (Fetch Metadata, Origin, en-tête applicatif), sans état côté serveur.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> configurerSessions(session, sessionRegistry))
        .exceptionHandling(ex -> configurerExceptions(ex, logAdminService));

    return http.build();
  }

  private void configurerHeaders(final HeadersConfigurer<HttpSecurity> headers) {
    headers
        .httpStrictTransportSecurity(
            hsts -> hsts.maxAgeInSeconds(63_072_000).includeSubDomains(true))
        .referrerPolicy(
            referrer ->
                referrer.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
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
        .addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow"))
        .addHeaderWriter(
            new StaticHeadersWriter(
                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"));
  }

  private void configurerAutorisations(
      final AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          auth) {
    auth.requestMatchers(
            "/assets/**",
            "/images/**",
            "/icons/**",
            "/favicon.ico",
            "/robots.txt",
            "/manifest.json",
            "/sw.js",
            "/offline.html",
            "/offline-fallback.js",
            "/index.html")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/api/push")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/api/incident")
        .permitAll()
        .requestMatchers("/actuator/health")
        .permitAll()
        .requestMatchers(
            "/api/auth/login",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/type-token")
        .permitAll()
        .requestMatchers("/login", "/forgot-password", "/reset-password", "/setup")
        .permitAll()
        .requestMatchers("/api/setup")
        .permitAll()
        .requestMatchers(HttpMethod.POST, "/api/webhooks/resend")
        .permitAll()
        .requestMatchers(HttpMethod.POST, "/api/interne/backup-statut")
        .permitAll()
        .requestMatchers(HttpMethod.POST, "/api/interne/integrite-statut")
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
        .authenticated();
  }

  private void configurerSessions(
      final SessionManagementConfigurer<HttpSecurity> session,
      final SessionRegistry sessionRegistry) {
    session
        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
        .maximumSessions(10)
        .sessionRegistry(sessionRegistry);
  }

  private void configurerExceptions(
      final ExceptionHandlingConfigurer<HttpSecurity> ex, final LogAdminService logAdminService) {
    ex.authenticationEntryPoint(
            (request, response, authEx) ->
                ReponsesAccesApi.echecAuthentification(request, response, logAdminService))
        .accessDeniedHandler(
            (request, response, accessEx) ->
                ReponsesAccesApi.accesRefuse(request, response, logAdminService));
  }
}
