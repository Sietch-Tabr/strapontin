/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final ConfigService configService;

  public ModeleGlobalAdvice(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
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
  public String theme(
      HttpServletRequest request, HttpServletResponse response, Authentication auth) {
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

  /// Injecte le nombre de concerts non encore notifiés (badge bottom nav).
  ///
  /// <p>Ne compte que les matches par artiste suivi — les concerts des salles suivies
  /// sont accessibles via /spectacles et ne contribuent pas au badge.
  ///
  /// <p>Requête ignorée pour les endpoints {@code /api/} et les utilisateurs non authentifiés.
  ///
  /// @param auth    l'authentification Spring Security (peut être {@code null})
  /// @param request la requête HTTP
  /// @return le nombre de matches artiste avec {@code notifie_le IS NULL}, ou 0
  @ModelAttribute("nbNouveau")
  public int nbNouveau(Authentication auth, HttpServletRequest request) {
    if (auth == null
        || !auth.isAuthenticated()
        || request.getRequestURI().startsWith("/api/")
        || !(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      return 0;
    }
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM user_matches um
            JOIN concerts c ON c.id = um.concert_id
            WHERE um.user_id = :userId
              AND um.notifie_le IS NULL
              AND um.masque_le IS NULL
              AND EXISTS (
                SELECT 1 FROM user_artistes ua
                WHERE ua.user_id = :userId
                  AND (c.artiste_norm = ua.artiste_normalise
                       OR c.artiste_norm LIKE ua.artiste_normalise || ' %')
              )
            """,
            Map.of("userId", ud.id()), Integer.class);
    return count != null ? count : 0;
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

  /// Injecte le message de bannière système pour le rendu SSR initial.
  ///
  /// <p>Ignoré pour les endpoints {@code /api/} afin d'éviter une lecture DB inutile
  /// sur les appels AJAX et le poll HTMX lui-même.
  ///
  /// @param request la requête HTTP
  /// @return le message de bannière, ou {@code null} si absent
  @ModelAttribute("banniereMessage")
  public String banniereMessage(HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/")) return null;
    return configService.lireConfig("BANNER_MESSAGE").orElse(null);
  }

  /// Injecte le type de bannière système ({@code info}, {@code warning}, {@code error}).
  ///
  /// @param request la requête HTTP
  /// @return le type de bannière
  @ModelAttribute("banniereType")
  public String banniereType(HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/")) return null;
    return configService.lireConfig("BANNER_TYPE").orElse("info");
  }

  /// Injecte l'identifiant de bannière système (timestamp ISO de publication).
  ///
  /// @param request la requête HTTP
  /// @return l'identifiant de bannière
  @ModelAttribute("banniereId")
  public String banniereId(HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/")) return null;
    return configService.lireConfig("BANNER_ID").orElse("");
  }

  /// Items de navigation principale : affichés dans la sidebar et la bottom nav.
  @ModelAttribute("navPrincipal")
  public List<NavigationItem> navPrincipal() {
    return List.of(
        new NavigationItem("/", "accueil", "Accueil", "icon-home"),
        new NavigationItem("/spectacles", "spectacles", "Mes spectacles", "icon-ticket"),
        new NavigationItem("/decouverte", "decouverte", "Découverte", "icon-compass"));
  }

  /// Items de navigation secondaire : affichés dans la sidebar et le drawer mobile.
  @ModelAttribute("navSecondaire")
  public List<NavigationItem> navSecondaire() {
    return List.of(
        new NavigationItem("/artistes", "artistes", "Mes artistes", "icon-music"),
        new NavigationItem("/salles", "salles", "Mes salles", "icon-building-2"));
  }

  /// Items de navigation pied de sidebar : affichés dans la sidebar footer et le drawer mobile.
  @ModelAttribute("navFooter")
  public List<NavigationItem> navFooter() {
    return List.of(
        new NavigationItem("/parametres", "parametres", "Paramètres", "icon-settings"),
        new NavigationItem("/aide", "aide", "Aide", "icon-help-circle"),
        new NavigationItem("/changelog", "changelog", "Nouveautés", "icon-sparkles"));
  }

  /// Ensemble des identifiants de page qui activent le bouton « Plus » de la bottom nav.
  @ModelAttribute("pagesDrawer")
  public Set<String> pagesDrawer() {
    return Set.of("artistes", "salles", "admin", "parametres", "aide", "changelog");
  }
}
