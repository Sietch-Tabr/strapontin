/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link MiseAJourAlerteController}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class MiseAJourAlerteControllerTest {

  private static final String SECRET = "secret-de-test";
  private static final String JETON = "Bearer " + SECRET;

  private AlerteMiseAJourService alerte;
  private LogAdminService journal;
  private MiseAJourAlerteController controller;

  @BeforeEach
  void preparer() {
    alerte = mock(AlerteMiseAJourService.class);
    journal = mock(LogAdminService.class);
    controller = new MiseAJourAlerteController(alerte, journal, SECRET);
  }

  @Test
  void signalerStatut_jetonInvalide_retourne403SansRienFaire() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("reussi", "3.2.0", "ok", "Bearer faux");

    assertEquals(HttpStatus.FORBIDDEN, reponse.getStatusCode());
    verify(alerte, never()).envoyer(anyBoolean(), anyString(), anyString());
    verify(journal, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void signalerStatut_secretNonConfigure_retourne403() {
    final MiseAJourAlerteController sansSecret = new MiseAJourAlerteController(alerte, journal, "");

    assertEquals(
        HttpStatus.FORBIDDEN,
        sansSecret.signalerStatut("reussi", "3.2.0", "ok", "Bearer ").getStatusCode());
  }

  @Test
  void signalerStatut_reussi_journaliseEnInfoEtAlerte() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("reussi", "3.2.0", "Version 3.2.0 installée.", JETON);

    assertEquals(HttpStatus.OK, reponse.getStatusCode());
    verify(journal).logAdmin(eq("info"), eq("web"), anyString(), anyMap());
    verify(alerte).envoyer(true, "3.2.0", "Version 3.2.0 installée.");
  }

  @Test
  void signalerStatut_echec_journaliseEnErreurEtAlerte() {
    controller.signalerStatut("echec", "3.2.0", "Sauvegarde impossible", JETON);

    verify(journal).logAdmin(eq("error"), eq("web"), anyString(), anyMap());
    verify(alerte).envoyer(false, "3.2.0", "Sauvegarde impossible");
  }

  @Test
  void signalerStatut_resultatInconnu_retourne400SansAlerte() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("autre", "3.2.0", "x", JETON);

    assertEquals(HttpStatus.BAD_REQUEST, reponse.getStatusCode());
    verify(alerte, never()).envoyer(anyBoolean(), anyString(), anyString());
  }

  @Test
  void signalerStatut_messageTropLong_estTronque() {
    controller.signalerStatut("echec", "3.2.0", "x".repeat(900), JETON);

    verify(alerte).envoyer(false, "3.2.0", "x".repeat(500));
  }
}
