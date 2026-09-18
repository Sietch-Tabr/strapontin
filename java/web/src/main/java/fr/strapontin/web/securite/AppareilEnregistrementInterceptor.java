/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enregistre l'appareil courant dans {@code session_appareils},
 * au premier appel API suivant la connexion (détail : CLAUDE.md,
 * Authentification).
 * @since 2.7.8
 * @author Kwaite
 */
@Component
public class AppareilEnregistrementInterceptor implements HandlerInterceptor {

  private static final String ATTRIBUT_DEJA_ENREGISTRE = "appareilEnregistre";

  private final AppareilsService appareilsService;

  /**
   * @param appareilsService service d'enregistrement des appareils
   */
  public AppareilEnregistrementInterceptor(final AppareilsService appareilsService) {
    this.appareilsService = appareilsService;
  }

  @Override
  public boolean preHandle(
      final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
    final HttpSession session = request.getSession(false);
    if (session == null || session.getAttribute(ATTRIBUT_DEJA_ENREGISTRE) != null) {
      return true;
    }

    final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      return true;
    }

    appareilsService.enregistrerAppareil(
        session.getId(), details.id(), request.getHeader("User-Agent"));
    session.setAttribute(ATTRIBUT_DEJA_ENREGISTRE, Boolean.TRUE);
    return true;
  }
}
