/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.utilisateur.UserController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link TotpChallengeService} (extrait d'{@code AuthController}).
 *
 * @since 2.7.0
 * @author Kwaite
 */
class TotpChallengeServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private UserController userController;
  private TotpService totpService;
  private LogAdminService logAdminService;
  private TotpChallengeService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    userController = mock(UserController.class);
    totpService = mock(TotpService.class);
    logAdminService = mock(LogAdminService.class);
    service = new TotpChallengeService(jdbc, userController, totpService, logAdminService);
  }

  @Test
  void verifier_codeCorrect_retourneSuccesEtRemetCompteurAZero() {
    when(userController.lireEchecsTotp("u1")).thenReturn(0);
    when(userController.lireSecretTotp("u1")).thenReturn("SECRETBASE32");
    when(totpService.verifierCode("SECRETBASE32", "123456")).thenReturn(true);

    final ResultatTotp resultat =
        service.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "123456", "127.0.0.1"));

    assertEquals(ResultatTotp.Statut.SUCCES, resultat.statut());
    verify(jdbc).update(contains("totp_failure_count = 0"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("auth"), anyString(), anyMap());
  }

  @Test
  void verifier_codeIncorrect_retourneEchecAvecEssaisRestants() {
    when(userController.lireEchecsTotp("u1")).thenReturn(0);
    when(userController.lireSecretTotp("u1")).thenReturn("SECRETBASE32");
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    final ResultatTotp resultat =
        service.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1"));

    assertEquals(ResultatTotp.Statut.ECHEC, resultat.statut());
    assertEquals(4, resultat.essaisRestants());
    verify(jdbc).update(contains("totp_failure_count + 1"), anyMap());
    verify(logAdminService).logAdmin(eq("warn"), eq("auth"), anyString(), anyMap());
  }

  @Test
  void verifier_dernierEchecAvantSeuil_retourneVerrouilleMaintenant() {
    // 4 échecs déjà enregistrés → le 5e déclenche le verrouillage
    when(userController.lireEchecsTotp("u1")).thenReturn(4);
    when(userController.lireSecretTotp("u1")).thenReturn("SECRETBASE32");
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    final ResultatTotp resultat =
        service.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1"));

    assertEquals(ResultatTotp.Statut.VERROUILLE_MAINTENANT, resultat.statut());
  }

  @Test
  void verifier_compteurDejaAuSeuil_retourneDejaVerrouilleSansVerifierLeCode() {
    // Compte déjà verrouillé en base (≥ MAX_ECHECS_TOTP) — aucun code ne doit être vérifié
    when(userController.lireEchecsTotp("u1")).thenReturn(5);

    final ResultatTotp resultat =
        service.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1"));

    assertEquals(ResultatTotp.Statut.DEJA_VERROUILLE, resultat.statut());
    verify(totpService, never()).verifierCode(anyString(), anyString());
  }
}
