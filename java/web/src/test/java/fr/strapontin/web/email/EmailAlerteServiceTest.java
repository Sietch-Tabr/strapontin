/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.web.config.ConfigService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link EmailAlerteService} (#925, extrait d'{@code EmailService}).
///
/// <p>Couvre le garde-fou anti-boucle (#907) sur les notifications bounce/plainte spam. Les
/// méthodes d'envoi réel (dépendant du SDK Resend, non injectable) ne sont testées que sur leurs
/// branches de retour anticipé — le SDK n'effectue aucun appel réseau tant que
/// {@code emails().send()} n'est pas invoqué.
///
/// @since 2.7.0
/// @author Kwaite
class EmailAlerteServiceTest {

  private ConfigService configService;
  private EmailSender emailSender;
  private EmailAlerteService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    emailSender = mock(EmailSender.class);
    service = new EmailAlerteService(configService, emailSender);
  }

  @Test
  void envoyerNotificationBounce_resendApiKeyAbsent_neResoutPasLeDestinataire() {
    when(emailSender.obtenirContexte()).thenReturn(Optional.empty());

    service.envoyerNotificationBounce("rejete@exemple.fr");

    verify(emailSender, never()).obtenirDestinataireAlerte();
  }

  @Test
  void envoyerNotificationBounce_alerteRecente_ignoreSansResoudreLeDestinataire() {
    when(emailSender.obtenirContexte())
        .thenReturn(Optional.of(new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "")));
    when(configService.lireConfig("DERNIERE_ALERTE_BOUNCE_ENVOYEE"))
        .thenReturn(Optional.of(Instant.now().toString()));

    service.envoyerNotificationBounce("rejete@exemple.fr");

    verify(emailSender, never()).obtenirDestinataireAlerte();
    verify(configService, never())
        .sauvegarderConfig(eq("DERNIERE_ALERTE_BOUNCE_ENVOYEE"), anyString());
  }

  @Test
  void envoyerNotificationBounce_aucunDestinataireDisponible_neLeveAucuneException() {
    when(emailSender.obtenirContexte())
        .thenReturn(Optional.of(new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "")));
    when(configService.lireConfig("DERNIERE_ALERTE_BOUNCE_ENVOYEE")).thenReturn(Optional.empty());
    when(emailSender.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.envoyerNotificationBounce("rejete@exemple.fr");

    verify(configService, never())
        .sauvegarderConfig(eq("DERNIERE_ALERTE_BOUNCE_ENVOYEE"), anyString());
  }

  @Test
  void envoyerNotificationPlainteSpam_alerteRecente_ignoreSansResoudreLeDestinataire() {
    when(emailSender.obtenirContexte())
        .thenReturn(Optional.of(new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "")));
    when(configService.lireConfig("DERNIERE_ALERTE_SPAM_ENVOYEE"))
        .thenReturn(Optional.of(Instant.now().toString()));

    service.envoyerNotificationPlainteSpam("plaignant@exemple.fr");

    verify(emailSender, never()).obtenirDestinataireAlerte();
  }

  // --- envoyerAlerteQuotaResend (#981) ---

  private static final CompteurEnvoisResend COMPTEUR_SEUIL_JOUR =
      new CompteurEnvoisResend("2026-07-18", 85, "2026-07", 200, false, false);

  // --- envoyerAlerteIntegrite (#976) ---

  @Test
  void envoyerAlerteIntegrite_resendApiKeyAbsent_neResoutPasLeDestinataire() {
    when(emailSender.obtenirContexte()).thenReturn(Optional.empty());

    service.envoyerAlerteIntegrite("web.jar modifié");

    verify(emailSender, never()).obtenirDestinataireAlerte();
  }

  @Test
  void envoyerAlerteIntegrite_aucunDestinataireDisponible_neLeveAucuneException() {
    when(emailSender.obtenirContexte())
        .thenReturn(Optional.of(new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "")));
    when(emailSender.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.envoyerAlerteIntegrite("web.jar modifié");

    verify(emailSender, never())
        .envoyer(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void envoyerAlerteIntegrite_destinataireDisponible_envoie() {
    when(emailSender.obtenirContexte())
        .thenReturn(
            Optional.of(
                new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "https://exemple.fr")));
    when(emailSender.obtenirDestinataireAlerte()).thenReturn(Optional.of("admin@exemple.fr"));

    service.envoyerAlerteIntegrite("web.jar modifié");

    verify(emailSender)
        .envoyer(
            any(),
            eq("admin@exemple.fr"),
            eq("noreply@exemple.fr"),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void envoyerAlerteQuotaResend_resendApiKeyAbsent_retourneFalse() {
    when(emailSender.obtenirContexte()).thenReturn(Optional.empty());

    boolean resultat = service.envoyerAlerteQuotaResend("admin@exemple.fr", COMPTEUR_SEUIL_JOUR);

    assertFalse(resultat);
  }

  @Test
  void envoyerAlerteQuotaResend_envoiReussi_retourneTrue() {
    when(emailSender.obtenirContexte())
        .thenReturn(
            Optional.of(
                new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "https://exemple.fr")));

    boolean resultat = service.envoyerAlerteQuotaResend("admin@exemple.fr", COMPTEUR_SEUIL_JOUR);

    assertTrue(resultat);
    verify(emailSender)
        .envoyer(
            any(),
            eq("admin@exemple.fr"),
            eq("noreply@exemple.fr"),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void envoyerAlerteQuotaResend_echecEnvoi_retourneFalse() {
    when(emailSender.obtenirContexte())
        .thenReturn(
            Optional.of(
                new EmailSender.ContexteEmail(null, "noreply@exemple.fr", "https://exemple.fr")));
    doThrow(new IllegalStateException("échec Resend"))
        .when(emailSender)
        .envoyer(any(), anyString(), anyString(), anyString(), anyString(), anyString());

    boolean resultat = service.envoyerAlerteQuotaResend("admin@exemple.fr", COMPTEUR_SEUIL_JOUR);

    assertFalse(resultat);
  }
}
