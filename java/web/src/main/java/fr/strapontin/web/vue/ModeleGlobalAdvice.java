/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/// Injecte les attributs globaux dans chaque modèle Thymeleaf.
///
/// <p>Expose le thème et les informations de l'utilisateur courant dans tous les templates.
/// Le thème est lu depuis le cookie {@code theme} en priorité ; si absent, depuis
/// {@code user_config} en base (puis le cookie est posé pour les prochaines requêtes).
///
/// @since 2.0.0
/// @author Kwaite
@ControllerAdvice
public class ModeleGlobalAdvice {

  @Value("${app.cookie.secure:true}")
  private boolean cookieSecure;

  private final NamedParameterJdbcTemplate jdbc;

  public ModeleGlobalAdvice(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Injecte le thème courant.
  ///
  /// <p>Lit d'abord le cookie {@code theme} (fast path). Si absent et utilisateur
  /// authentifié, lit depuis {@code user_config} et pose le cookie pour les prochaines
  /// requêtes. Évite la requête DB sur les endpoints {@code /api/}.
  ///
  /// @param request  la requête HTTP
  /// @param response la réponse HTTP (pour poser le cookie si nécessaire)
  /// @param auth     l'authentification Spring Security (peut être {@code null})
  /// @return la valeur du thème ({@code auto}, {@code clair} ou {@code sombre})
  @ModelAttribute("theme")
  public String theme(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
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
    // Fallback DB : uniquement pour les pages HTML (pas les endpoints /api/)
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails ud
        && !request.getRequestURI().startsWith("/api/")) {
      List<String> themes =
          jdbc.queryForList(
              "SELECT theme FROM user_config WHERE user_id = :userId",
              Map.of("userId", ud.id()),
              String.class);
      if (!themes.isEmpty() && themes.get(0) != null) {
        String themeDb = themes.get(0);
        if ("sombre".equals(themeDb) || "clair".equals(themeDb)) {
          Cookie cookie = new Cookie("theme", themeDb);
          cookie.setMaxAge(365 * 24 * 3600);
          cookie.setPath("/");
          cookie.setHttpOnly(false);
          cookie.setSecure(cookieSecure);
          cookie.setAttribute("SameSite", "Strict");
          response.addCookie(cookie);
          return themeDb;
        }
      }
    }
    return "auto";
  }

  /// Injecte l'utilisateur courant dans le modèle.
  ///
  /// @param auth l'authentification Spring Security (peut être {@code null})
  /// @return les détails de l'utilisateur ou null si non connecté
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
