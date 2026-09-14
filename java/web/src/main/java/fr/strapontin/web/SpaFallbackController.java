/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fallback SPA Vue 3 : renvoie {@code index.html} pour toute route sans
 * point dans le chemin (laisse Vue Router gérer la navigation), les assets
 * statiques (.js, .css, .ico) contenant un point n'étant pas interceptés ;
 * les routes {@code /api/**} sont gérées par les contrôleurs REST avant.
 * @since 2.2.0
 * @author Kwaite
 */
@Controller
class SpaFallbackController {

  /* package-private : instancié uniquement par Spring. */
  SpaFallbackController() {}

  /**
   * Capture les routes à un ou plusieurs segments sans extension et renvoie
   * {@code index.html} .
   *
   * @return le forward vers {@code /index.html}
   */
  @GetMapping({"/{chemin:[^\\.]+}", "/**/{chemin:[^\\.]+}"})
  /* package-private : appelé uniquement par Spring MVC (mapping HTTP). */
  String spaFallback() {
    return "forward:/index.html";
  }
}
