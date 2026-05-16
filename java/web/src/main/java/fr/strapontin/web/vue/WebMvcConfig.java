/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Configuration Spring MVC — enregistre les intercepteurs Thymeleaf.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final FirstLoginIntercepteur firstLoginIntercepteur;

  public WebMvcConfig(FirstLoginIntercepteur firstLoginIntercepteur) {
    this.firstLoginIntercepteur = firstLoginIntercepteur;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(firstLoginIntercepteur);
  }
}
