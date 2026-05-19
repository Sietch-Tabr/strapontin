/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/// Intercepteur qui redirige vers {@code /first-login} si l'utilisateur doit changer ses
// credentials.
///
/// <p>Appliqué à toutes les pages protégées sauf {@code /first-login} lui-même
/// et les routes API (gérées séparément).
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class FirstLoginIntercepteur implements HandlerInterceptor {

  private static final String URL_FIRST_LOGIN = "/first-login";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String chemin = request.getServletPath();
    if (chemin.startsWith("/api/") || chemin.equals(URL_FIRST_LOGIN)) {
      return true;
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails u
        && u.doitChangerCredentials()) {
      response.sendRedirect(URL_FIRST_LOGIN);
      return false;
    }

    return true;
  }
}
