/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link EmailService} (emails transactionnels utilisateur).
 *
 * <p>Depuis, les alertes système (bounce/spam/backup/watchdog/logs-admin) sont testées dans
 * {@code EmailAlerteServiceTest}, et la résolution du destinataire d'alerte dans
 * {@code EmailSenderTest}. Les méthodes d'envoi réel de ce service (dépendant du SDK Resend, non
 * injectable) ne sont testées que sur leurs branches de retour anticipé (RESEND_API_KEY absent) —
 * le SDK n'effectue aucun appel réseau tant que {@code emails().send()} n'est pas invoqué.
 *
 * @since 2.0.0
 * @author Kwaite
 */
class EmailServiceTest {

  private ConfigService configService;
  private EmailService emailService;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    EmailSender emailSender = mock(EmailSender.class);
    emailService = new EmailService(configService, emailSender);
  }

  @Test
  void estDisponible_resendApiKeyPresente_retourneVrai() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123456"));

    assertTrue(emailService.estDisponible());
  }

  @Test
  void estDisponible_resendApiKeyAbsente_retourneFaux() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    assertFalse(emailService.estDisponible());
  }
}
