/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.miseajour.EtatMiseAJourWeb;
import fr.strapontin.web.miseajour.MiseAJourService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link MiseAJourInterneController}, dont le garde-fou : hors du profil
 * {@code dev}, ses routes n'existent pas.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class MiseAJourInterneControllerTest {

  private static final String SECRET = "secret-de-test";
  private static final String JETON = "Bearer " + SECRET;

  private MiseAJourService service;
  private LogAdminService journal;
  private MiseAJourInterneController controller;

  @BeforeEach
  void preparer() {
    service = mock(MiseAJourService.class);
    journal = mock(LogAdminService.class);
    controller = new MiseAJourInterneController(service, journal, SECRET);
  }

  @Test
  void demander_jetonInvalide_retourne403SansRienDemander() {
    final ResponseEntity<?> reponse = controller.demander("Bearer faux");

    assertEquals(HttpStatus.FORBIDDEN, reponse.getStatusCode());
    verify(service, never()).demanderVersionProposee(anyString());
  }

  @Test
  void demander_secretNonConfigure_retourne403() {
    final MiseAJourInterneController sansSecret =
        new MiseAJourInterneController(service, journal, "");

    assertEquals(HttpStatus.FORBIDDEN, sansSecret.demander("Bearer ").getStatusCode());
    verify(service, never()).demanderVersionProposee(anyString());
  }

  @Test
  void demander_jetonValide_deposeLaDemandeEtLaJournalise() {
    when(service.demanderVersionProposee("api-dev")).thenReturn("3.2.0");

    final ResponseEntity<?> reponse = controller.demander(JETON);

    assertEquals(HttpStatus.ACCEPTED, reponse.getStatusCode());
    assertTrue(String.valueOf(reponse.getBody()).contains("3.2.0"));
    verify(journal).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void demander_aucuneVersionProposee_laisseRemonterLErreurMetier() {
    when(service.demanderVersionProposee("api-dev"))
        .thenThrow(new ErreurMetier("Aucune version n'est proposée."));

    assertThrows(ErreurMetier.class, () -> controller.demander(JETON));
    verify(journal, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void etat_jetonInvalide_retourne403() {
    assertEquals(HttpStatus.FORBIDDEN, controller.etat("Bearer faux").getStatusCode());
    verify(service, never()).etat(true);
  }

  @Test
  void etat_jetonValide_retourneLEtat() {
    final EtatMiseAJourWeb etat = new EtatMiseAJourWeb("3.2.0", null, null, false, false, true);
    when(service.etat(true)).thenReturn(etat);

    final ResponseEntity<?> reponse = controller.etat(JETON);

    assertEquals(HttpStatus.OK, reponse.getStatusCode());
    assertEquals(etat, reponse.getBody());
  }

  @Import(MiseAJourInterneController.class)
  static class Configuration {}

  private ApplicationContextRunner contexte(final String profil) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Configuration.class)
        .withBean(MiseAJourService.class, () -> service)
        .withBean(LogAdminService.class, () -> journal)
        .withPropertyValues("spring.profiles.active=" + profil, "UPDATE_INTERNAL_SECRET=x");
  }

  @Test
  void profilDev_leControleurExiste() {
    contexte("dev")
        .run(c -> assertEquals(1, c.getBeansOfType(MiseAJourInterneController.class).size()));
  }

  @Test
  void profilProd_leControleurNExistePas() {
    contexte("prod")
        .run(c -> assertEquals(0, c.getBeansOfType(MiseAJourInterneController.class).size()));
  }

  @Test
  void sansProfil_leControleurNExistePas() {
    new ApplicationContextRunner()
        .withUserConfiguration(Configuration.class)
        .withBean(MiseAJourService.class, () -> service)
        .withBean(LogAdminService.class, () -> journal)
        .run(c -> assertEquals(0, c.getBeansOfType(MiseAJourInterneController.class).size()));
  }
}
