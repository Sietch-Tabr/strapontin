/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre de garde TOTP : bloque les appels API tant que le
 * challenge 2FA n'est pas validé (détail : CLAUDE.md,
 * Authentification).
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class TotpGuardFilter extends OncePerRequestFilter {

  private static final Set<String> API_TOTP_AUTORISES =
      Set.of("/api/auth/totp", "/api/auth/logout");

  /** Constructeur par défaut, instancié par Spring. */
  public TotpGuardFilter() {
    super();
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final HttpSession session = request.getSession(false);
    if (session != null
        && Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE))) {
      final String chemin = request.getServletPath();
      if (chemin.startsWith("/api/") && !API_TOTP_AUTORISES.contains(chemin)) {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"erreur\":\"TOTP requis\"}");
        return;
      }
    }

    filterChain.doFilter(request, response);
  }
}
