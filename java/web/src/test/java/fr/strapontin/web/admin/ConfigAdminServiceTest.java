/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link ConfigAdminService} (#916, extrait de {@code AdminService}).
///
/// @since 2.1.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
class ConfigAdminServiceTest {

  private ConfigService configService;
  private LogAdminService logAdminService;
  private ConfigAdminService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    service = new ConfigAdminService(configService, logAdminService, mock(RestClient.class));
  }

  @Test
  void sauvegarderConfig_cleNonAutorisee_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class, () -> service.sauvegarderConfig("CLE_INCONNUE", "valeur", "admin-1"));
  }

  @Test
  void sauvegarderConfig_valeurVidePourCleObligatoire_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class, () -> service.sauvegarderConfig("CONTACT_EMAIL", "", "admin-1"));
  }

  @Test
  void sauvegarderConfig_formatTicketmasterInvalide_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> service.sauvegarderConfig("TICKETMASTER_API_KEY", "trop court", "admin-1"));
  }

  @Test
  void sauvegarderConfig_valeurValide_sauvegardeEtLogue() {
    service.sauvegarderConfig("CONTACT_EMAIL", "contact@strapontin.fr", "admin-1");
    verify(configService).sauvegarderConfig("CONTACT_EMAIL", "contact@strapontin.fr");
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void sauvegarderConfig_adminAlertEmail_sauvegarde() {
    service.sauvegarderConfig("ADMIN_ALERT_EMAIL", "alerte@strapontin.fr", "admin-1");
    verify(configService).sauvegarderConfig("ADMIN_ALERT_EMAIL", "alerte@strapontin.fr");
  }

  @Test
  void sauvegarderConfig_formatVapidPublicInvalide_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> service.sauvegarderConfig("VAPID_PUBLIC_KEY", "n'importe-quoi", "admin-1"));
  }

  @Test
  void sauvegarderConfig_formatVapidPrivateInvalide_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> service.sauvegarderConfig("VAPID_PRIVATE_KEY", "n'importe-quoi", "admin-1"));
  }

  @Test
  void sauvegarderConfig_formatVapidPublicValide_sauvegarde() {
    String clePublique = "A".repeat(87);
    service.sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique, "admin-1");
    verify(configService).sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique);
  }

  @Test
  void sauvegarderConfig_formatVapidPrivateValide_sauvegarde() {
    String clePrivee = "A".repeat(43);
    service.sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee, "admin-1");
    verify(configService).sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee);
  }
}
