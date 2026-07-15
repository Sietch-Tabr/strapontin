/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link AlerteLogsAdminService}.
///
/// <p>Couvre le curseur persisté : n'avance qu'après envoi réussi (#765), pas de re-notification.
///
/// @since 2.6.1
/// @author Kwaite
class AlerteLogsAdminServiceTest {

  private static final String CLE_CURSEUR = "ALERTE_LOGS_ADMIN_CURSEUR";

  private LogAdminService logAdminService;
  private ConfigService configService;
  private EmailService emailService;
  private AlerteLogsAdminService service;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    configService = mock(ConfigService.class);
    emailService = mock(EmailService.class);
    service = new AlerteLogsAdminService(logAdminService, configService, emailService);
  }

  @Test
  void verifier_aucuneErreur_neFaitRienEtNAppellePasEmail() {
    when(configService.lireConfig(CLE_CURSEUR)).thenReturn(Optional.empty());
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(List.of());

    service.verifier();

    verify(emailService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(eq(CLE_CURSEUR), anyString());
  }

  @Test
  void verifier_erreursPresentesEtEmailConfigure_envoieEtAvanceLeCurseurAuDernierTs() {
    List<EntreeLogAdmin> erreurs =
        List.of(
            new EntreeLogAdmin(100L, "error", "worker", "échec job 1", null),
            new EntreeLogAdmin(200L, "error", "worker", "échec job 2", null));
    when(configService.lireConfig(CLE_CURSEUR)).thenReturn(Optional.empty());
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(erreurs);
    when(emailService.obtenirDestinataireAlerte()).thenReturn(Optional.of("admin@exemple.fr"));
    when(emailService.envoyerAlerteLogsAdmin("admin@exemple.fr", erreurs)).thenReturn(true);

    service.verifier();

    verify(configService).sauvegarderConfig(CLE_CURSEUR, "200");
  }

  @Test
  void verifier_aucunDestinataireAlerte_neAvancePasLeCurseur() {
    List<EntreeLogAdmin> erreurs =
        List.of(new EntreeLogAdmin(100L, "error", "worker", "échec", null));
    when(configService.lireConfig(CLE_CURSEUR)).thenReturn(Optional.empty());
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(erreurs);
    when(emailService.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.verifier();

    verify(emailService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(eq(CLE_CURSEUR), anyString());
  }

  @Test
  void verifier_echecEnvoiEmail_neAvancePasLeCurseur() {
    List<EntreeLogAdmin> erreurs =
        List.of(new EntreeLogAdmin(100L, "error", "worker", "échec", null));
    when(configService.lireConfig(CLE_CURSEUR)).thenReturn(Optional.empty());
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(erreurs);
    when(emailService.obtenirDestinataireAlerte()).thenReturn(Optional.of("admin@exemple.fr"));
    when(emailService.envoyerAlerteLogsAdmin("admin@exemple.fr", erreurs)).thenReturn(false);

    service.verifier();

    verify(configService, never()).sauvegarderConfig(eq(CLE_CURSEUR), anyString());
  }

  @Test
  void verifier_curseurExistant_neReinterrogePasLesErreursDejaCouvertes() {
    when(configService.lireConfig(CLE_CURSEUR)).thenReturn(Optional.of("150"));
    when(logAdminService.lireErreursDepuis(150L)).thenReturn(List.of());

    service.verifier();

    verify(logAdminService).lireErreursDepuis(150L);
    verify(logAdminService, never()).lireErreursDepuis(0L);
  }

  @SuppressWarnings("unchecked")
  private static List<EntreeLogAdmin> anyList() {
    return org.mockito.ArgumentMatchers.any(List.class);
  }
}
