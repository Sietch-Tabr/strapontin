/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CouvertureDepartementsAdminControllerTest {

  private CouvertureDepartementsAdminService service;
  private CouvertureDepartementsAdminController controller;

  @BeforeEach
  void setUp() {
    service = mock(CouvertureDepartementsAdminService.class);
    controller = new CouvertureDepartementsAdminController(service);
  }

  @Test
  void reconnaitre_codeInvalide_retourne400SansInterrogerLeService() {
    assertEquals(400, controller.reconnaitre("../etc").getStatusCode().value());
    verify(service, never()).reconnaitre("../etc");
  }

  @Test
  void reconnaitre_departementAvecConcerts_retourne404() {
    when(service.reconnaitre("75")).thenReturn(false);

    assertEquals(404, controller.reconnaitre("75").getStatusCode().value());
  }

  @Test
  void reconnaitre_departementSansConcert_retourne200() {
    when(service.reconnaitre("971")).thenReturn(true);

    assertEquals(200, controller.reconnaitre("971").getStatusCode().value());
  }

  @Test
  void reconnaitre_corse_accepteLeCodeAlphanumerique() {
    when(service.reconnaitre("2A")).thenReturn(true);

    assertEquals(200, controller.reconnaitre("2A").getStatusCode().value());
  }

  @Test
  void rouvrir_aucuneReconnaissance_retourne404() {
    when(service.rouvrir("971")).thenReturn(false);

    assertEquals(404, controller.rouvrir("971").getStatusCode().value());
  }

  @Test
  void rouvrir_codeInvalide_retourne400() {
    assertEquals(400, controller.rouvrir("x").getStatusCode().value());
  }
}
