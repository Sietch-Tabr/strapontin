/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link TotpChallengeService} (extrait d'{@code AuthController}).
///
/// @since 2.7.0
/// @author Kwaite
class TotpChallengeServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private TotpService totpService;
  private LogAdminService logAdminService;
  private TotpChallengeService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    totpService = mock(TotpService.class);
    logAdminService = mock(LogAdminService.class);
    service = new TotpChallengeService(jdbc, totpService, logAdminService);
  }

  @Test
  void verifier_codeCorrect_retourneSuccesEtRemetCompteurAZero() {
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 0));
    when(totpService.verifierCode("SECRETBASE32", "123456")).thenReturn(true);

    ResultatTotp resultat = service.verifier("u1", "alice", "123456", "127.0.0.1");

    assertEquals(ResultatTotp.Statut.SUCCES, resultat.statut());
    verify(jdbc).update(contains("totp_failure_count = 0"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("auth"), anyString(), anyMap());
  }

  @Test
  void verifier_codeIncorrect_retourneEchecAvecEssaisRestants() {
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 0));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    ResultatTotp resultat = service.verifier("u1", "alice", "000000", "127.0.0.1");

    assertEquals(ResultatTotp.Statut.ECHEC, resultat.statut());
    assertEquals(4, resultat.essaisRestants());
    verify(jdbc).update(contains("totp_failure_count + 1"), anyMap());
    verify(logAdminService).logAdmin(eq("warn"), eq("auth"), anyString(), anyMap());
  }

  @Test
  void verifier_dernierEchecAvantSeuil_retourneVerrouilleMaintenant() {
    // 4 échecs déjà enregistrés → le 5e déclenche le verrouillage
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 4));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    ResultatTotp resultat = service.verifier("u1", "alice", "000000", "127.0.0.1");

    assertEquals(ResultatTotp.Statut.VERROUILLE_MAINTENANT, resultat.statut());
  }

  @Test
  void verifier_compteurDejaAuSeuil_retourneDejaVerrouilleSansVerifierLeCode() {
    // Compte déjà verrouillé en base (≥ MAX_ECHECS_TOTP) — aucun code ne doit être vérifié
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 5));

    ResultatTotp resultat = service.verifier("u1", "alice", "000000", "127.0.0.1");

    assertEquals(ResultatTotp.Statut.DEJA_VERROUILLE, resultat.statut());
    verify(totpService, never()).verifierCode(anyString(), anyString());
  }
}
