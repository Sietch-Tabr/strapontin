/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/// Fallback SPA Vue 3 : renvoie {@code index.html} pour toute route sans point dans le chemin,
/// laissant Vue Router gérer la navigation côté client.
///
/// <p>Les assets statiques (`.js`, `.css`, `.ico`, etc.) contiennent un point et ne sont donc
/// pas interceptés par ce mapping. Les routes {@code /api/**} sont gérées par les
/// {@code @RestController} avant d'atteindre ce fallback.
///
/// <p>Si la requête provient de HTMX (en-tête {@code HX-Request: true}), répond avec
/// {@code HX-Redirect} pour forcer un rechargement complet de la page au lieu d'un swap partiel
/// — indispensable pendant la coexistence Thymeleaf/Vue.
///
/// @since 2.2.0
/// @author Kwaite
@Controller
class SpaFallbackController {

  /// Capture les routes à un ou plusieurs segments sans extension.
  ///
  /// @param request  la requête HTTP entrante
  /// @param response la réponse HTTP à construire
  /// @return le forward vers {@code /index.html}, ou {@code null} si HTMX gère la redirection
  @GetMapping({"/{chemin:[^\\.]+}", "/**/{chemin:[^\\.]+}"})
  String spaFallback(HttpServletRequest request, HttpServletResponse response) {
    if ("true".equals(request.getHeader("HX-Request"))) {
      String chemin = request.getRequestURI();
      if (request.getQueryString() != null) {
        chemin += "?" + request.getQueryString();
      }
      response.setHeader("HX-Redirect", chemin);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return null;
    }
    return "forward:/index.html";
  }
}
