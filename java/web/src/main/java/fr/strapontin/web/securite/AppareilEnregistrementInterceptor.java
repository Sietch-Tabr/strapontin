/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
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

  // Les appels parallèles de la première page après connexion voient tous
  // l'attribut absent : seul le premier enregistre, sinon chacun réinsère
  // l'attribut de session (clé dupliquée) et l'alerte part plusieurs fois.
  private final Cache<String, Boolean> enregistrementsEnCours =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();

  private final AppareilsService appareilsService;
  private final AlerteActiviteUtilisateurService alerteActiviteUtilisateurService;

  /**
   * @param appareilsService service d'enregistrement des appareils
   * @param alerteActiviteUtilisateurService alertes de nouvel appareil
   */
  public AppareilEnregistrementInterceptor(
      final AppareilsService appareilsService,
      final AlerteActiviteUtilisateurService alerteActiviteUtilisateurService) {
    this.appareilsService = appareilsService;
    this.alerteActiviteUtilisateurService = alerteActiviteUtilisateurService;
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

    if (enregistrementsEnCours.asMap().putIfAbsent(session.getId(), Boolean.TRUE) != null) {
      return true;
    }
    final String userAgent = request.getHeader("User-Agent");
    final String modeAffichage = request.getHeader(NomAppareil.ENTETE_MODE);
    final boolean nouvelAppareil =
        appareilsService.estNouvelAppareil(details.id(), userAgent, modeAffichage);
    appareilsService.enregistrerAppareil(session.getId(), details.id(), userAgent, modeAffichage);
    if (nouvelAppareil) {
      alerteActiviteUtilisateurService.notifierNouvelAppareil(
          details.email(), userAgent, modeAffichage);
    }
    session.setAttribute(ATTRIBUT_DEJA_ENREGISTRE, Boolean.TRUE);
    return true;
  }
}
