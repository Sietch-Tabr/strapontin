/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * Configuration Spring Security 7 pour le mode SPA Vue 3.
 *
 * <p>Sessions persistantes JDBC (survivent aux redémarrages). Rate limiting login via {@link
 * RateLimitingLoginFilter} (intercepte {@code /api/auth/login}). Accès direct par IP bloqué via
 * {@link BloquerAccesDirectIpFilter}. CSRF désactivé (sessions {@code SameSite=Lax} — pas {@code
 * Strict}, compatibilité Safari sur navigation entrante, voir {@link
 * SessionSecurityConfiguration#cookieSerializer} — + {@code form-action 'self'} en CSP). Login via
 * {@code POST /api/auth/login}, logout via {@code POST /api/auth/logout}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

  public SecurityConfiguration() {}

  /**
   * Registre de sessions persisté en JDBC — survit aux redémarrages.
   *
   * @param sessionRepository le dépôt de sessions Spring Session JDBC
   * @return le registre de sessions
   */
  @Bean
  SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }

  /**
   * Chaîne de filtres de sécurité principale.
   *
   * @param http le constructeur de configuration Spring Security
   * @param correlationIdFilter le filtre de corrélation MDC (premier dans la chaîne)
   * @param rateLimitingFilter le filtre de rate limiting sur le login
   * @param bloquerIpFilter le filtre de blocage d'accès direct par IP
   * @param totpGuardFilter le filtre de garde TOTP post-login
   * @param sessionRegistry le registre de sessions persisté en JDBC
   * @param logAdminService le service de journalisation admin
   * @return la SecurityFilterChain configurée
   * @throws Exception en cas d'erreur de configuration
   */
  // throws Exception imposé par HttpSecurity.build() (SecurityBuilder<O>), pas modifiable.
  @Bean
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
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
        .authorizeHttpRequests(this::configurerAutorisations)
        // CSRF désactivé : sessions SameSite=Lax + HttpOnly (identique v1)
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
        .requestMatchers("/actuator/health")
        .permitAll()
        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
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
                gererEchecAuthentification(request, response, logAdminService))
        .accessDeniedHandler(
            (request, response, accessEx) -> gererAccesRefuse(request, response, logAdminService));
  }

  private void gererEchecAuthentification(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final LogAdminService logAdminService)
      throws IOException {
    if (request.getServletPath().startsWith("/api/")) {
      try {
        final Map<String, Object> meta = new HashMap<>();
        meta.put("chemin", request.getServletPath());
        meta.put("ip", request.getRemoteAddr());
        final String ua = request.getHeader("User-Agent");
        meta.put("userAgent", ua != null ? ua.substring(0, Math.min(ua.length(), 200)) : "");
        logAdminService.logAdmin(
            "warn", "web", "Accès API non authentifié : " + request.getServletPath(), meta);
      } catch (Exception e) {
        LOG.warn("échec de la journalisation d'un accès non authentifié", e);
      }
      response.setStatus(401);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Authentification requise\"}");
    } else {
      response.sendRedirect("/login");
    }
  }

  private void gererAccesRefuse(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final LogAdminService logAdminService)
      throws IOException {
    if (request.getServletPath().startsWith("/api/")) {
      try {
        final Map<String, Object> meta = new HashMap<>();
        meta.put("chemin", request.getServletPath());
        meta.put("ip", request.getRemoteAddr());
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
          meta.put("userId", auth.getName());
        }
        logAdminService.logAdmin(
            "warn", "web", "Accès API refusé : " + request.getServletPath(), meta);
      } catch (Exception e) {
        LOG.warn("échec de la journalisation d'un accès refusé", e);
      }
      response.setStatus(403);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"erreur\":\"Accès refusé\"}");
    } else {
      response.sendRedirect("/login");
    }
  }
}
