/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link BanniereService}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
class BanniereServiceTest {

  private ConfigService configService;
  private LogAdminService logAdminService;
  private BanniereService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    service = new BanniereService(configService, logAdminService);
  }

  @Test
  void publierBanniere_sauvegardeMessageTypeEtId() {
    service.publierBanniere("Maintenance prévue", "warning");

    verify(configService).sauvegarderConfig("BANNER_MESSAGE", "Maintenance prévue");
    verify(configService).sauvegarderConfig("BANNER_TYPE", "warning");
    verify(configService)
        .sauvegarderConfig(
            org.mockito.ArgumentMatchers.eq("BANNER_ID"), org.mockito.ArgumentMatchers.anyString());
    verify(logAdminService).logAdmin("info", "admin", "Bannière publiée : warning");
  }

  @Test
  void effacerBanniere_effaceLesTroisClesEtLogue() {
    service.effacerBanniere();

    verify(configService).sauvegarderConfig("BANNER_MESSAGE", null);
    verify(configService).sauvegarderConfig("BANNER_TYPE", null);
    verify(configService).sauvegarderConfig("BANNER_ID", null);
    verify(logAdminService).logAdmin("info", "admin", "Bannière effacée");
  }
}
