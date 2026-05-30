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

/// Filtre de garde TOTP : bloque l'accès tant que le challenge 2FA n'est pas validé.
///
/// <p>Si la session contient {@code TOTP_EN_ATTENTE=true} et que la requête ne cible pas
/// une ressource autorisée, redirige vers {@code /auth/totp}.
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class TotpGuardFilter extends OncePerRequestFilter {

  private static final Set<String> CHEMINS_AUTORISES =
      Set.of("/auth/totp", "/logout", "/favicon.ico");
  private static final String[] PREFIXES_AUTORISES =
      new String[] {"/css/", "/js/", "/icons/", "/images/", "/sw.js", "/offline.html"};

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    HttpSession session = request.getSession(false);
    if (session != null
        && Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE))) {
      String chemin = request.getServletPath();
      if (!estAutorise(chemin)) {
        response.sendRedirect("/auth/totp");
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean estAutorise(String chemin) {
    if (CHEMINS_AUTORISES.contains(chemin)) return true;
    for (String prefixe : PREFIXES_AUTORISES) {
      if (chemin.startsWith(prefixe)) return true;
    }
    return false;
  }
}
