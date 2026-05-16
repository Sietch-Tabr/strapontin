/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/// Injecte les attributs globaux dans chaque modèle Thymeleaf.
///
/// <p>Expose le thème (depuis le cookie {@code theme}) et les informations
/// de l'utilisateur courant dans tous les templates.
///
/// @since 2.0.0
/// @author Kwaite
@ControllerAdvice
public class ModeleGlobalAdvice {

  /// Injecte le thème courant depuis le cookie.
  ///
  /// @param request la requête HTTP
  /// @return la valeur du thème ({@code auto}, {@code clair} ou {@code sombre})
  @ModelAttribute("theme")
  public String theme(HttpServletRequest request) {
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if ("theme".equals(cookie.getName())) {
          String valeur = cookie.getValue();
          if ("sombre".equals(valeur) || "clair".equals(valeur) || "auto".equals(valeur)) {
            return valeur;
          }
        }
      }
    }
    return "auto";
  }

  /// Injecte l'utilisateur courant dans le modèle.
  ///
  /// @param auth l'authentification Spring Security (peut être {@code null})
  /// @return les détails de l'utilisateur ou {@code null} si non connecté
  @ModelAttribute("utilisateur")
  public UtilisateurDetails utilisateur(Authentication auth) {
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails u) {
      return u;
    }
    return null;
  }
}
