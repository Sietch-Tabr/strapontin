/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailAlerteService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link AlerteLogsAdminService}.
 *
 * <p>Couvre le curseur persisté (n'avance qu'après envoi réussi, jamais de re-notification) et
 * le plafond journalier de digests.
 *
 * @since 2.6.1
 * @author Kwaite
 */
class AlerteLogsAdminServiceTest {

  private static final String CLE_CURSEUR_TS = "ALERTE_LOGS_ADMIN_CURSEUR";
  private static final String CLE_CURSEUR_ID = "ALERTE_LOGS_ADMIN_CURSEUR_ID";
  private static final String CLE_ENVOIS_JOUR = "ALERTE_LOGS_ADMIN_ENVOIS_JOUR";
  private static final String EMAIL = "admin@exemple.fr";

  private LogAdminService logAdminService;
  private ConfigService configService;
  private EmailAlerteService emailAlerteService;
  private AlerteLogsAdminService service;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    configService = mock(ConfigService.class);
    emailAlerteService = mock(EmailAlerteService.class);
    when(configService.lireConfig(anyString())).thenReturn(Optional.empty());
    when(emailAlerteService.envoiDisponible()).thenReturn(true);
    service =
        new AlerteLogsAdminService(
            logAdminService,
            configService,
            emailAlerteService,
            Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void verifier_aucuneErreur_neFaitRienEtNAppellePasEmail() {
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(List.of());

    service.verifier();

    verify(emailAlerteService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(anyString(), anyString());
  }

  @Test
  void verifier_erreursPresentesEtEmailConfigure_envoieEtAvanceLeCurseurAuDernierId() {
    final List<EntreeLogAdmin> erreurs = List.of(erreur(7L), erreur(9L));
    preparerEnvoi(erreurs, true);

    service.verifier();

    verify(configService).sauvegarderConfig(CLE_CURSEUR_ID, "9");
    verify(configService).sauvegarderConfig(CLE_ENVOIS_JOUR, "2026-09-30:1");
  }

  @Test
  void verifier_aucunDestinataireAlerte_neAvancePasLeCurseur() {
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(List.of(erreur(1L)));
    when(emailAlerteService.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.verifier();

    verify(emailAlerteService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(eq(CLE_CURSEUR_ID), anyString());
  }

  @Test
  void verifier_echecEnvoiEmail_neAvancePasLeCurseur() {
    preparerEnvoi(List.of(erreur(1L)), false);

    service.verifier();

    verify(configService, never()).sauvegarderConfig(eq(CLE_CURSEUR_ID), anyString());
  }

  @Test
  void verifier_curseurExistant_neReinterrogePasLesErreursDejaCouvertes() {
    when(configService.lireConfig(CLE_CURSEUR_ID)).thenReturn(Optional.of("150"));
    when(logAdminService.lireErreursDepuis(150L)).thenReturn(List.of());

    service.verifier();

    verify(logAdminService).lireErreursDepuis(150L);
    verify(logAdminService, never()).lireErreursDepuis(0L);
  }

  @Test
  void verifier_ancienCurseurTemporelSeul_leConvertitEnIdentifiant() {
    when(configService.lireConfig(CLE_CURSEUR_TS)).thenReturn(Optional.of("1759131811247"));
    when(logAdminService.idMaxJusquA(1759131811247L)).thenReturn(42L);
    when(logAdminService.lireErreursDepuis(42L)).thenReturn(List.of());

    service.verifier();

    verify(logAdminService).lireErreursDepuis(42L);
  }

  @Test
  void verifier_plafondJournalierAtteint_nEnvoiePasEtConserveLeCurseur() {
    when(configService.lireConfig(CLE_ENVOIS_JOUR))
        .thenReturn(Optional.of("2026-09-30:" + AlerteLogsAdminService.MAX_DIGESTS_PAR_JOUR));
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(List.of(erreur(1L)));

    service.verifier();

    verify(emailAlerteService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(anyString(), anyString());
  }

  @Test
  void verifier_plafondAtteintLaVeille_repartAZero() {
    when(configService.lireConfig(CLE_ENVOIS_JOUR))
        .thenReturn(Optional.of("2026-09-29:" + AlerteLogsAdminService.MAX_DIGESTS_PAR_JOUR));
    preparerEnvoi(List.of(erreur(1L)), true);

    service.verifier();

    verify(configService).sauvegarderConfig(CLE_ENVOIS_JOUR, "2026-09-30:1");
  }

  private void preparerEnvoi(final List<EntreeLogAdmin> erreurs, final boolean succes) {
    when(logAdminService.lireErreursDepuis(0L)).thenReturn(erreurs);
    when(emailAlerteService.obtenirDestinataireAlerte()).thenReturn(Optional.of(EMAIL));
    when(emailAlerteService.envoyerAlerteLogsAdmin(EMAIL, erreurs)).thenReturn(succes);
  }

  private static EntreeLogAdmin erreur(final long id) {
    return new EntreeLogAdmin(id, 1000L * id, "error", "worker", "échec " + id, null);
  }

  // Sans service d'email, rien ne peut partir : aucune tentative, curseur conservé.
  @Test
  void verifier_aucunServiceDEmail_neTentePasDEnvoiNiNAvanceLeCurseur() {
    when(emailAlerteService.envoiDisponible()).thenReturn(false);
    when(logAdminService.lireErreursDepuis(0L))
        .thenReturn(List.of(mock(LogAdminService.EntreeLogAdmin.class)));

    service.verifier();
    service.verifier();

    verify(emailAlerteService, never()).envoyerAlerteLogsAdmin(anyString(), anyList());
    verify(configService, never()).sauvegarderConfig(anyString(), anyString());
  }
}
