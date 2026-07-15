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

import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/// Tests unitaires pour {@link EmailService}.
///
/// <p>Couvre {@link EmailService#obtenirDestinataireAlerte()} (#907 — ne se replie jamais sur
/// l'expéditeur/CONTACT_EMAIL) et le garde-fou anti-boucle sur les notifications bounce/plainte
/// spam. Les méthodes d'envoi réel (dépendant du SDK Resend, non injectable) ne sont testées que
/// sur leurs branches de retour anticipé — le SDK n'effectue aucun appel réseau tant que
/// {@code emails().send()} n'est pas invoqué.
///
/// @since 2.7.0
/// @author Kwaite
class EmailServiceTest {

  private ConfigService configService;
  private JdbcTemplate jdbcTemplate;
  private EmailService emailService;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    emailService = new EmailService(configService, jdbcTemplate);
  }

  @Test
  void obtenirDestinataireAlerte_adminAlertEmailConfigure_retourneCetteAdresse() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL"))
        .thenReturn(Optional.of("alerte@exemple.fr"));

    Optional<String> resultat = emailService.obtenirDestinataireAlerte();

    assertEquals(Optional.of("alerte@exemple.fr"), resultat);
    verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class));
  }

  @Test
  void obtenirDestinataireAlerte_adminAlertEmailAbsent_replieSurPremierAdmin() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL")).thenReturn(Optional.empty());
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenReturn("premier-admin@exemple.fr");

    Optional<String> resultat = emailService.obtenirDestinataireAlerte();

    assertEquals(Optional.of("premier-admin@exemple.fr"), resultat);
  }

  @Test
  void obtenirDestinataireAlerte_niConfigNiAdmin_retourneVide() {
    when(configService.lireConfig("ADMIN_ALERT_EMAIL")).thenReturn(Optional.empty());
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    Optional<String> resultat = emailService.obtenirDestinataireAlerte();

    assertTrue(resultat.isEmpty());
  }

  @Test
  void envoyerNotificationBounce_resendApiKeyAbsent_neResoutPasLeDestinataire() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    emailService.envoyerNotificationBounce("rejete@exemple.fr");

    verify(configService, never()).lireConfig("ADMIN_ALERT_EMAIL");
  }

  @Test
  void envoyerNotificationBounce_alerteRecente_ignoreSansResoudreLeDestinataire() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123456"));
    when(configService.lireConfig("DERNIERE_ALERTE_BOUNCE_ENVOYEE"))
        .thenReturn(Optional.of(java.time.Instant.now().toString()));

    emailService.envoyerNotificationBounce("rejete@exemple.fr");

    verify(configService, never()).lireConfig("ADMIN_ALERT_EMAIL");
    verify(configService, never())
        .sauvegarderConfig(eq("DERNIERE_ALERTE_BOUNCE_ENVOYEE"), anyString());
  }

  @Test
  void envoyerNotificationBounce_aucunDestinataireDisponible_neLeveAucuneException() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123456"));
    when(configService.lireConfig("DERNIERE_ALERTE_BOUNCE_ENVOYEE")).thenReturn(Optional.empty());
    when(configService.lireConfig("ADMIN_ALERT_EMAIL")).thenReturn(Optional.empty());
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    emailService.envoyerNotificationBounce("rejete@exemple.fr");

    verify(configService, never())
        .sauvegarderConfig(eq("DERNIERE_ALERTE_BOUNCE_ENVOYEE"), anyString());
  }

  @Test
  void envoyerNotificationPlainteSpam_alerteRecente_ignoreSansResoudreLeDestinataire() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123456"));
    when(configService.lireConfig("DERNIERE_ALERTE_SPAM_ENVOYEE"))
        .thenReturn(Optional.of(java.time.Instant.now().toString()));

    emailService.envoyerNotificationPlainteSpam("plaignant@exemple.fr");

    verify(configService, never()).lireConfig("ADMIN_ALERT_EMAIL");
  }
}
