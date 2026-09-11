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
 * Enregistre l'appareil courant dans {@code session_appareils}, au premier appel API qui suit la
 * connexion — jamais pendant la requête de login elle-même.
 *
 * <p>Spring Session (JDBC) ne persiste la session en base qu'à la toute fin de son propre filtre,
 * après que le contrôleur de login a déjà rendu sa réponse : une écriture dans {@code
 * session_appareils} (qui référence {@code SPRING_SESSION} par clé étrangère) tentée depuis le
 * contrôleur de login viole systématiquement cette contrainte, la ligne n'existant pas encore. Cet
 * intercepteur, exécuté par Spring MVC sur les requêtes API suivantes, s'exécute nécessairement
 * après qu'une réponse précédente (donc la persistance de session qu'elle déclenche) est terminée.
 *
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
  public AppareilEnregistrementInterceptor(AppareilsService appareilsService) {
    this.appareilsService = appareilsService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    HttpSession session = request.getSession(false);
    if (session == null || session.getAttribute(ATTRIBUT_DEJA_ENREGISTRE) != null) {
      return true;
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
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
