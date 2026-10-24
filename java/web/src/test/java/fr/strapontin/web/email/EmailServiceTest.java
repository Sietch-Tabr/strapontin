/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    final EmailSender emailSender = mock(EmailSender.class);
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

  // L'invité n'a pas encore de compte : le texte brut dit la même chose que le HTML.
  @Test
  void envoyerInvitation_texteBrut_invitePlutotQueDeParlerDeCompteDejaCree() {
    final EmailSender emailSender = mock(EmailSender.class);
    when(emailSender.obtenirContexte())
        .thenReturn(
            Optional.of(
                new EmailSender.ContexteEmail(
                    null, "Strapontin <noreply@strapontin.fr>", "https://app.fr")));
    final ArgumentCaptor<String> texte = ArgumentCaptor.forClass(String.class);

    new EmailService(configService, emailSender)
        .envoyerInvitation("alice@exemple.fr", "https://app.fr/setup/abc");

    verify(emailSender)
        .envoyer(any(), anyString(), anyString(), anyString(), anyString(), texte.capture());
    assertTrue(texte.getValue().contains("Vous avez été invité à rejoindre Strapontin"));
    assertFalse(texte.getValue().contains("compte a été créé"));
  }
}
