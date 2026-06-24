/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Configuration Spring MVC — ressources statiques.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // sw.js et index.html : no-cache (le navigateur doit toujours vérifier les mises à jour).
    registry
        .addResourceHandler("/sw.js", "/index.html")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache().mustRevalidate());

    // CSS : no-cache car les noms de fichiers sont fixes (pas de fingerprinting dans l'URL).
    // Spring extrait la partie après /css/ du chemin et la concatène à l'emplacement,
    // donc l'emplacement doit pointer sur classpath:/static/css/ (pas classpath:/static/).
    registry
        .addResourceHandler("/css/**")
        .addResourceLocations("classpath:/static/css/")
        .setCacheControl(CacheControl.noCache().mustRevalidate());
  }
}
