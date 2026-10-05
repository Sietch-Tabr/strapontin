/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resend.Resend;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link PurgeCompteEmailService}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class PurgeCompteEmailServiceTest {

  private ConfigServiceWorker configService;
  private GestionQuotaResendWorker gestionQuotaResendWorker;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigServiceWorker.class);
    gestionQuotaResendWorker = mock(GestionQuotaResendWorker.class);
  }

  @Test
  void envoyerRelanceInactivite_cleAbsente_neTenteAucunEnvoi() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    final PurgeCompteEmailService service =
        new PurgeCompteEmailService(
            configService, gestionQuotaResendWorker, cle -> mock(Resend.class));

    assertFalse(service.envoyerRelanceInactivite("a@test.fr", 15));

    verify(gestionQuotaResendWorker, never()).reserverEnvoi();
  }

  @Test
  void envoyerRelanceInactivite_quotaAtteint_neContactePasResend() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(false);
    final PurgeCompteEmailService service =
        new PurgeCompteEmailService(
            configService, gestionQuotaResendWorker, cle -> mock(Resend.class));

    assertFalse(service.envoyerRelanceInactivite("a@test.fr", 15));
  }

  @Test
  void envoyerRelanceInactivite_succes_envoieViaResend() throws Exception {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("contact@test.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(true);
    final Resend resendMock = mock(Resend.class);
    final Emails emailsMock = mock(Emails.class);
    when(resendMock.emails()).thenReturn(emailsMock);
    final PurgeCompteEmailService service =
        new PurgeCompteEmailService(configService, gestionQuotaResendWorker, cle -> resendMock);

    assertTrue(service.envoyerRelanceInactivite("a@test.fr", 15));

    verify(emailsMock).send(any(CreateEmailOptions.class));
  }

  @Test
  void envoyerRelanceInvitation_succes_envoieViaResend() throws Exception {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("contact@test.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(true);
    final Resend resendMock = mock(Resend.class);
    final Emails emailsMock = mock(Emails.class);
    when(resendMock.emails()).thenReturn(emailsMock);
    final PurgeCompteEmailService service =
        new PurgeCompteEmailService(configService, gestionQuotaResendWorker, cle -> resendMock);

    assertTrue(
        service.envoyerRelanceInvitation(
            "b@test.fr",
            "https://strapontin.fr/reset-password?token=abc",
            LocalDate.of(2026, 10, 10)));

    verify(emailsMock).send(any(CreateEmailOptions.class));
  }

  @Test
  void envoyerRelanceInvitation_echecResend_retourneFalseSansException() throws Exception {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("contact@test.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(true);
    final Resend resendMock = mock(Resend.class);
    final Emails emailsMock = mock(Emails.class);
    when(resendMock.emails()).thenReturn(emailsMock);
    when(emailsMock.send(any(CreateEmailOptions.class))).thenThrow(new RuntimeException("panne"));
    final PurgeCompteEmailService service =
        new PurgeCompteEmailService(configService, gestionQuotaResendWorker, cle -> resendMock);

    assertFalse(
        service.envoyerRelanceInvitation(
            "b@test.fr", "https://strapontin.fr/x", LocalDate.of(2026, 10, 10)));
  }
}
