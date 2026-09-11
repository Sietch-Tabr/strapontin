/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link ParametresService}.
 *
 * @since 2.5.4
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class ParametresServiceTest {

  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private EmailService emailService;
  @Mock private UtilisateurDetails details;
  @Mock private UtilisateurRepository utilisateurRepository;

  private ParametresService service;

  @BeforeEach
  void setUp() {
    service = new ParametresService(jdbc, emailService, utilisateurRepository);
  }

  @Test
  void stats_retourneNbArtistesEtSalles() {
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class))).thenReturn(3);
    when(jdbc.queryForObject(contains("user_salles"), anyMap(), eq(Integer.class))).thenReturn(5);

    Map<String, Object> res = service.stats("u1");

    assertEquals(3, res.get("nbArtistes"));
    assertEquals(5, res.get("nbSalles"));
  }

  @Test
  void stats_valeursNulles_retourneZero() {
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class)))
        .thenReturn(null);
    when(jdbc.queryForObject(contains("user_salles"), anyMap(), eq(Integer.class)))
        .thenReturn(null);

    Map<String, Object> res = service.stats("u1");

    assertEquals(0, res.get("nbArtistes"));
    assertEquals(0, res.get("nbSalles"));
  }

  @Test
  void emailDejaUtilise_emailPris_retourneTrue() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of(1));

    assertTrue(service.emailDejaUtilise("autre@example.com", "u1"));
  }

  @Test
  void emailDejaUtilise_emailLibre_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());

    assertFalse(service.emailDejaUtilise("libre@example.com", "u1"));
  }

  @Test
  void emailDisponible_delegueSurEmailService() {
    when(emailService.estDisponible()).thenReturn(true);

    assertTrue(service.emailDisponible());
    verify(emailService).estDisponible();
  }

  @Test
  void demanderChangementEmail_insereVerificationEtEnvoieEmail() {
    when(details.id()).thenReturn("u1");
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.demanderChangementEmail(details, "nouveau@example.com", "http://localhost");

    verify(jdbc).update(contains("DELETE FROM verifications"), anyMap());
    verify(jdbc).update(contains("INSERT INTO verifications"), anyMap());
    verify(jdbc).update(contains("UPDATE users SET pending_email"), anyMap());
    ArgumentCaptor<String> lienCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailService).envoyerConfirmationEmail(eq("nouveau@example.com"), lienCaptor.capture());
    assertTrue(
        lienCaptor.getValue().startsWith("http://localhost/api/parametres/confirmer-email?token="));
  }

  @Test
  void confirmerEmail_tokenAbsent_retourneLienInvalide() {
    ParametresService.ResultatConfirmation res = service.confirmerEmail(null, "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.LIEN_INVALIDE, res.statut());
    verifyNoInteractions(jdbc);
  }

  @Test
  void confirmerEmail_tokenVide_retourneLienInvalide() {
    ParametresService.ResultatConfirmation res = service.confirmerEmail("", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.LIEN_INVALIDE, res.statut());
    verifyNoInteractions(jdbc);
  }

  @Test
  void confirmerEmail_tokenExpire_retourneLienExpire() {
    when(jdbc.queryForList(contains("SELECT identifier"), anyMap())).thenReturn(List.of());

    ParametresService.ResultatConfirmation res = service.confirmerEmail("tokenexpire", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.LIEN_EXPIRE, res.statut());
  }

  @Test
  void confirmerEmail_mauvaisUtilisateur_retourneMauvaisUtilisateur() {
    when(jdbc.queryForList(contains("SELECT identifier"), anyMap()))
        .thenReturn(List.of(Map.of("identifier", "email-change:autreUser")));

    ParametresService.ResultatConfirmation res = service.confirmerEmail("token123", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.MAUVAIS_UTILISATEUR, res.statut());
  }

  @Test
  void confirmerEmail_aucunPendingEmail_retourneAucunChangement() {
    when(jdbc.queryForList(contains("SELECT identifier"), anyMap()))
        .thenReturn(List.of(Map.of("identifier", "email-change:u1")));
    when(jdbc.queryForList(contains("SELECT pending_email"), anyMap(), eq(String.class)))
        .thenReturn(List.of());

    ParametresService.ResultatConfirmation res = service.confirmerEmail("tokenok", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.AUCUN_CHANGEMENT, res.statut());
  }

  @Test
  void confirmerEmail_emailDejaPris_retourneEmailDejaUtilise() {
    when(jdbc.queryForList(contains("SELECT identifier"), anyMap()))
        .thenReturn(List.of(Map.of("identifier", "email-change:u1")));
    when(jdbc.queryForList(contains("SELECT pending_email"), anyMap(), eq(String.class)))
        .thenReturn(List.of("pris@example.com"));
    when(jdbc.queryForList(
            contains("SELECT 1 FROM users WHERE email"), anyMap(), eq(Integer.class)))
        .thenReturn(List.of(1));

    ParametresService.ResultatConfirmation res = service.confirmerEmail("tokenok", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.EMAIL_DEJA_UTILISE, res.statut());
    verify(jdbc).update(contains("UPDATE users SET pending_email = NULL"), anyMap());
    verify(jdbc).update(contains("DELETE FROM verifications"), anyMap());
  }

  @Test
  void confirmerEmail_ok_metsAJourEmailEtSupprimeVerification() {
    when(jdbc.queryForList(contains("SELECT identifier"), anyMap()))
        .thenReturn(List.of(Map.of("identifier", "email-change:u1")));
    when(jdbc.queryForList(contains("SELECT pending_email"), anyMap(), eq(String.class)))
        .thenReturn(List.of("nouveau@example.com"));
    when(jdbc.queryForList(
            contains("SELECT 1 FROM users WHERE email"), anyMap(), eq(Integer.class)))
        .thenReturn(List.of());

    ParametresService.ResultatConfirmation res = service.confirmerEmail("tokenok", "u1");

    assertEquals(ParametresService.ResultatConfirmation.Statut.OK, res.statut());
    assertEquals("u1", res.userId());
    verify(jdbc).update(contains("UPDATE users SET email ="), anyMap());
    verify(jdbc).update(contains("DELETE FROM verifications"), anyMap());
  }

  @Test
  void compterNouveauxMatchesArtistes_delegueAuRepository() {
    when(utilisateurRepository.compterNouveauxMatchesArtistes("u1")).thenReturn(7);

    assertEquals(7, service.compterNouveauxMatchesArtistes("u1"));
  }
}
