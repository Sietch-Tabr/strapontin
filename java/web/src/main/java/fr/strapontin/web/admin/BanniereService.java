/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.config.ConfigService;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Gestion de la bannière système (message diffusé à tous les utilisateurs).
 *
 * <p>La bannière est stockée dans {@code app_config} via trois clés :
 * {@code BANNER_MESSAGE}, {@code BANNER_TYPE} et {@code BANNER_ID} (horodatage
 * servant d'identifiant de version côté client).
 *
 * @since 2.2.3
 * @author Kwaite
 */
@Service
public class BanniereService {

  private final ConfigService configService;
  private final LogAdminService logAdminService;

  /**
   * @param configService    lecture/écriture de la configuration applicative
   * @param logAdminService  service de logs admin
   */
  public BanniereService(ConfigService configService, LogAdminService logAdminService) {
    this.configService = configService;
    this.logAdminService = logAdminService;
  }

  /**
   * Publie une bannière système.
   *
   * @param message texte de la bannière
   * @param type    type ({@code info}, {@code warning} ou {@code error})
   */
  public void publierBanniere(String message, String type) {
    configService.sauvegarderConfig("BANNER_MESSAGE", message);
    configService.sauvegarderConfig("BANNER_TYPE", type);
    configService.sauvegarderConfig("BANNER_ID", Instant.now().toString());
    logAdminService.logAdmin("info", "admin", "Bannière publiée : " + type);
  }

  /**
   * Efface la bannière système.
   */
  public void effacerBanniere() {
    configService.sauvegarderConfig("BANNER_MESSAGE", null);
    configService.sauvegarderConfig("BANNER_TYPE", null);
    configService.sauvegarderConfig("BANNER_ID", null);
    logAdminService.logAdmin("info", "admin", "Bannière effacée");
  }
}
