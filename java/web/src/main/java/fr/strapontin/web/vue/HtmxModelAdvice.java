/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/// Injecte {@code reqHtmx} dans le modèle de toutes les vues Thymeleaf.
///
/// <p>Utilisé dans {@code layout/base.html} pour supprimer le rendu de la
/// sidebar et de la navigation mobile sur les requêtes HTMX (hx-boost),
/// réduisant le payload réseau de 30 à 50 %.
///
/// @since 2.1.2
/// @author Kwaite
@ControllerAdvice
public class HtmxModelAdvice {

  /// {@code true} si la requête provient de HTMX (header {@code HX-Request: true}).
  ///
  /// @param request la requête HTTP courante
  /// @return {@code true} pour les requêtes HTMX, {@code false} sinon
  @ModelAttribute("reqHtmx")
  public boolean reqHtmx(HttpServletRequest request) {
    return "true".equals(request.getHeader("HX-Request"));
  }
}
