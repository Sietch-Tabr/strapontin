/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * Tests unitaires pour {@link ConfigAdminService} (extrait de {@code AdminService}).
 *
 * @since 2.1.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
class ConfigAdminServiceTest {

  private ConfigService configService;
  private LogAdminService logAdminService;
  private ConfigAdminService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    service =
        new ConfigAdminService(
            configService,
            logAdminService,
            mock(RestClient.class),
            mock(fr.strapontin.core.CadenceurAppels.class));
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
    final String clePublique = "A".repeat(87);
    service.sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique, "admin-1");
    verify(configService).sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique);
  }

  @Test
  void sauvegarderConfig_formatVapidPrivateValide_sauvegarde() {
    final String clePrivee = "A".repeat(43);
    service.sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee, "admin-1");
    verify(configService).sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee);
  }

  // Ticketmaster est aussi appelé directement par le worker (processus indépendant) : sans passer
  // par le cadenceur partagé avant l'appel HTTP, les deux processus ne peuvent pas coordonner leur
  // rythme d'appel et le rate-limit TM (Spike Arrest) reste exposé.
  @Test
  void testerApiTm_cleConfiguree_attendLeCadenceurAvantLAppelHttp() {
    when(configService.lireConfig("TICKETMASTER_API_KEY")).thenReturn(Optional.of("fake-key"));
    final fr.strapontin.core.CadenceurAppels cadenceur =
        mock(fr.strapontin.core.CadenceurAppels.class);

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(
            MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("app.ticketmaster.com")))
        .andRespond(MockRestResponseCreators.withSuccess());

    final ConfigAdminService serviceCadence =
        new ConfigAdminService(configService, logAdminService, restClient, cadenceur);

    final boolean resultat = serviceCadence.testerApiTm();

    assertTrue(resultat);
    verify(cadenceur).attendre();
    serveur.verify();
  }
}
