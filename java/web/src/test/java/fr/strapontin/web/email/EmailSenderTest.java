/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resend.Resend;
import com.resend.services.emails.Emails;
import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests unitaires pour {@link EmailSender} (extrait d'{@code EmailService}).
 *
 * <p>Couvre {@link EmailSender#obtenirDestinataireAlerte} (ne se replie jamais sur
 * l'expéditeur/CONTACT_EMAIL).
 *
 * @since 2.7.0
 * @author Kwaite
 */
class EmailSenderTest {

  private ConfigService configService;
  private JdbcTemplate jdbcTemplate;
  private GestionQuotaResend gestionQuotaResend;
  private EmailSender emailSender;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    gestionQuotaResend = mock(GestionQuotaResend.class);
    when(gestionQuotaResend.reserverEnvoi()).thenReturn(true);
    emailSender = new EmailSender(configService, jdbcTemplate, gestionQuotaResend);
  }

  @Test
  void obtenirDestinataireAlerte_adminAlertEmailConfigure_retourneCetteAdresse() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL"))
        .thenReturn(Optional.of("alerte@exemple.fr"));

    final Optional<String> resultat = emailSender.obtenirDestinataireAlerte();

    assertEquals(Optional.of("alerte@exemple.fr"), resultat);
    verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class));
  }

  @Test
  void obtenirDestinataireAlerte_adminAlertEmailAbsent_replieSurPremierAdmin() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL")).thenReturn(Optional.empty());
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenReturn("premier-admin@exemple.fr");

    final Optional<String> resultat = emailSender.obtenirDestinataireAlerte();

    assertEquals(Optional.of("premier-admin@exemple.fr"), resultat);
  }

  @Test
  void obtenirDestinataireAlerte_niConfigNiAdmin_retourneVide() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL")).thenReturn(Optional.empty());
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    final Optional<String> resultat = emailSender.obtenirDestinataireAlerte();

    assertTrue(resultat.isEmpty());
  }

  @Test
  void obtenirContexte_resendApiKeyAbsent_retourneVide() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    assertTrue(emailSender.obtenirContexte().isEmpty());
  }

  // --- envoyer (garde-fou quota Resend) ---

  @Test
  void envoyer_quotaResendAtteint_neContactePasResend() {
    when(gestionQuotaResend.reserverEnvoi()).thenReturn(false);
    final Resend client = mock(Resend.class);
    final EmailSender.ContexteEmail ctx =
        new EmailSender.ContexteEmail(client, "contact@exemple.fr", "https://exemple.fr");

    emailSender.envoyer(
        ctx, "dest@exemple.fr", "contact@exemple.fr", "Sujet", "<p>corps</p>", "texte");

    verify(client, never()).emails();
  }

  @Test
  void envoyer_quotaResendDisponible_contacteResend()
      throws com.resend.core.exception.ResendException {
    when(gestionQuotaResend.reserverEnvoi()).thenReturn(true);
    final Resend client = mock(Resend.class);
    final Emails emails = mock(Emails.class);
    when(client.emails()).thenReturn(emails);
    final EmailSender.ContexteEmail ctx =
        new EmailSender.ContexteEmail(client, "contact@exemple.fr", "https://exemple.fr");

    emailSender.envoyer(
        ctx, "dest@exemple.fr", "contact@exemple.fr", "Sujet", "<p>corps</p>", "texte");

    verify(emails).send(org.mockito.ArgumentMatchers.any());
  }
}
