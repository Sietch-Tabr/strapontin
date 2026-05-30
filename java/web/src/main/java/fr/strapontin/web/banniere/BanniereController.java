/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.banniere;

import fr.strapontin.web.config.ConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/// Retourne le fragment de bannière système, interrogé par HTMX toutes les 60 secondes.
///
/// Si `BANNER_MESSAGE` est absent de la configuration, le fragment est vide
/// et HTMX efface la bannière existante.
///
/// @since 2.2.0
/// @author Kwaite
@Controller
public class BanniereController {

  private final ConfigService configService;

  public BanniereController(ConfigService configService) {
    this.configService = configService;
  }

  /// Retourne le fragment de bannière pour le poll HTMX.
  ///
  /// @param modele le modèle Thymeleaf alimenté avec les données de bannière
  /// @return le nom du fragment Thymeleaf à rendre
  @GetMapping("/api/banniere")
  public String banniere(Model modele) {
    configService
        .lireConfig("BANNER_MESSAGE")
        .ifPresent(
            msg -> {
              modele.addAttribute("banniereMessage", msg);
              modele.addAttribute(
                  "banniereType", configService.lireConfig("BANNER_TYPE").orElse("info"));
              modele.addAttribute("banniereId", configService.lireConfig("BANNER_ID").orElse(""));
            });
    return "layout/fragments/banniere :: banniere";
  }
}
