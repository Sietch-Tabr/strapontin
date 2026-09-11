/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.banniere;

import fr.strapontin.web.config.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expose les données de la bannière système en JSON pour le layout Vue.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
public class BanniereController {

  private final ConfigService configService;

  /**
   * @param configService lecture de la configuration de la bannière
   */
  public BanniereController(final ConfigService configService) {
    this.configService = configService;
  }

  /**
   * Retourne les données de la bannière active, ou {@code null} si aucune n'est configurée.
   *
   * @return le DTO de bannière, ou {@code null}
   */
  @GetMapping("/api/banniere/json")
  public BanniereDto banniereJson() {
    return configService
        .lireConfig("BANNER_MESSAGE")
        .map(
            msg ->
                new BanniereDto(
                    msg,
                    configService.lireConfig("BANNER_TYPE").orElse("info"),
                    configService.lireConfig("BANNER_ID").orElse("")))
        .orElse(null);
  }

  /**
   * DTO de bannière pour le layout Vue.
   *
   * @param message le texte de la bannière
   * @param type le type ({@code info}, {@code warning}, {@code error})
   * @param id l'identifiant unique (timestamp ISO de publication)
   */
  public record BanniereDto(String message, String type, String id) {}
}
