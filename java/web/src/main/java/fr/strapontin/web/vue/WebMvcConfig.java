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
    // sw.js ne doit pas être mis en cache longue durée : le navigateur doit toujours vérifier les
    // mises à jour
    registry
        .addResourceHandler("/sw.js")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache().mustRevalidate());
  }
}
