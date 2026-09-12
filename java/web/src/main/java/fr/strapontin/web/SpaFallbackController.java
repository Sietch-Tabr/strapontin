/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fallback SPA Vue 3 : renvoie {@code index.html} pour toute route sans point dans le chemin,
 * laissant Vue Router gérer la navigation côté client.
 *
 * <p>Les assets statiques ({@code .js}, {@code .css}, {@code .ico}, etc.) contiennent un point et ne sont donc
 * pas interceptés par ce mapping. Les routes {@code /api/**} sont gérées par les
 * {@code @RestController} avant d'atteindre ce fallback.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@Controller
class SpaFallbackController {

  SpaFallbackController() {}

  /**
   * Capture les routes à un ou plusieurs segments sans extension et renvoie {@code index.html}.
   *
   * @return le forward vers {@code /index.html}
   */
  @GetMapping({"/{chemin:[^\\.]+}", "/**/{chemin:[^\\.]+}"})
  String spaFallback() {
    return "forward:/index.html";
  }
}
