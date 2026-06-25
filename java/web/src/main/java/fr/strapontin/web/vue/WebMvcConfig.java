/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import java.time.Duration;
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

    // Bundles Vite (URLs hashées) : immutable — l'URL change à chaque build,
    // donc le contenu est garanti stable pour une URL donnée.
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

    // Images et icônes : 1 semaine — URLs stables mais contenu pouvant changer à chaque release.
    registry
        .addResourceHandler("/images/**")
        .addResourceLocations("classpath:/static/images/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    registry
        .addResourceHandler("/icons/**")
        .addResourceLocations("classpath:/static/icons/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
  }
}
