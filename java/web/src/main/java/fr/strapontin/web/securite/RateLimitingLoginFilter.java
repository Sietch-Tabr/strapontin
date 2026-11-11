/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre de rate limiting sur les endpoints d'authentification
 * publics (détail : CLAUDE.md, Authentification).
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class RateLimitingLoginFilter extends OncePerRequestFilter {

  private static final int MAX_TENTATIVES_LOGIN = 15;
  private static final Duration FENETRE_LOGIN = Duration.ofMinutes(15);

  private static final int MAX_TENTATIVES_RESET = 30;
  private static final Duration FENETRE_RESET = Duration.ofHours(1);

  private static final Set<String> URLS_LOGIN =
      Set.of("/api/auth/login", "/api/auth/forgot-password");
  private static final String URL_RESET_PASSWORD = "/api/auth/reset-password";

  private final LogAdminService logAdminService;

  private final LimiteurDebit limiteurLogin =
      new LimiteurDebit(MAX_TENTATIVES_LOGIN, FENETRE_LOGIN, FENETRE_RESET.plusMinutes(5), 10_000);
  private final LimiteurDebit limiteurReset =
      new LimiteurDebit(MAX_TENTATIVES_RESET, FENETRE_RESET, FENETRE_RESET.plusMinutes(5), 10_000);

  /**
   * @param logAdminService journalisation des tentatives bloquées
   */
  public RateLimitingLoginFilter(final LogAdminService logAdminService) {
    super();
    this.logAdminService = logAdminService;
  }

  /**
   * Remet à zéro les limiteurs de connexion et de réinitialisation de mot de
   * passe (profil {@code dev}, appelé par le global-setup des tests E2E).
   */
  public void reinitialiserLimites() {
    limiteurLogin.reinitialiser();
    limiteurReset.reinitialiser();
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final String chemin = request.getServletPath();
    if (!HttpMethod.POST.matches(request.getMethod())
        || (!URLS_LOGIN.contains(chemin) && !URL_RESET_PASSWORD.equals(chemin))) {
      chain.doFilter(request, response);
      return;
    }

    final String ip = request.getRemoteAddr();
    final String cleBucket = AdresseClient.cle(ip) + ":" + chemin;
    final boolean estReset = URL_RESET_PASSWORD.equals(chemin);
    final LimiteurDebit limiteur = estReset ? limiteurReset : limiteurLogin;

    if (limiteur.autoriser(cleBucket)) {
      chain.doFilter(request, response);
    } else {
      logAdminService.logAdmin(
          "warn",
          "auth",
          "Rate limit connexion atteint : " + ip + " sur " + chemin,
          Map.of("ip", ip));
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write("{\"erreur\":\"Trop de tentatives. Réessayez dans quelques minutes.\"}");
    }
  }
}
