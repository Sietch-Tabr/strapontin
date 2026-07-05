/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.banniere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link BanniereController}.
///
/// @since 2.6.6
/// @author Kwaite
class BanniereControllerTest {

  private ConfigService configService;
  private BanniereController controller;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    controller = new BanniereController(configService);
  }

  @Test
  void banniereJson_aucuneBanniereConfiguree_retourneNull() {
    when(configService.lireConfig("BANNER_MESSAGE")).thenReturn(Optional.empty());

    assertNull(controller.banniereJson());
  }

  @Test
  void banniereJson_banniereConfiguree_retourneLeDtoComplet() {
    when(configService.lireConfig("BANNER_MESSAGE")).thenReturn(Optional.of("Maintenance"));
    when(configService.lireConfig("BANNER_TYPE")).thenReturn(Optional.of("warning"));
    when(configService.lireConfig("BANNER_ID")).thenReturn(Optional.of("2026-07-05T10:00:00Z"));

    BanniereController.BanniereDto dto = controller.banniereJson();

    assertEquals("Maintenance", dto.message());
    assertEquals("warning", dto.type());
    assertEquals("2026-07-05T10:00:00Z", dto.id());
  }

  @Test
  void banniereJson_typeEtIdAbsents_replientSurValeursParDefaut() {
    when(configService.lireConfig("BANNER_MESSAGE")).thenReturn(Optional.of("Maintenance"));
    when(configService.lireConfig("BANNER_TYPE")).thenReturn(Optional.empty());
    when(configService.lireConfig("BANNER_ID")).thenReturn(Optional.empty());

    BanniereController.BanniereDto dto = controller.banniereJson();

    assertEquals("info", dto.type());
    assertEquals("", dto.id());
  }
}
