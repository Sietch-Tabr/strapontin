/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires pour {@link MiseAJourAdminController}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class MiseAJourAdminControllerTest {

  private static final UtilisateurDetails ADMIN =
      new UtilisateurDetails("id-1", "kwaite", "k@exemple.fr", "hash", "admin", true, false, false);
  private static final RequeteMiseAJour REQUETE = new RequeteMiseAJour("3.2.0", "mdp", "123456");

  private MiseAJourService service;
  private ReauthentificationAdmin reauthentification;
  private LogAdminService journal;
  private Authentication auth;
  private MiseAJourAdminController controller;

  @BeforeEach
  void preparer() {
    service = mock(MiseAJourService.class);
    reauthentification = mock(ReauthentificationAdmin.class);
    journal = mock(LogAdminService.class);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ADMIN);
    controller = new MiseAJourAdminController(service, reauthentification, journal);
  }

  @Test
  void lire_retourneLEtatAvecLeTotpDeLAdministrateur() {
    final EtatMiseAJourWeb etat = new EtatMiseAJourWeb("3.1.5", "3.2.0", null, false, true);
    when(service.etat(true)).thenReturn(etat);

    final ResponseEntity<EtatMiseAJourWeb> reponse = controller.lire(auth);

    assertEquals(HttpStatus.OK, reponse.getStatusCode());
    assertEquals(etat, reponse.getBody());
  }

  @Test
  void demander_reauthentificationValide_deposeLaDemandeEtJournalise() {
    final ResponseEntity<Map<String, Object>> reponse = controller.demander(REQUETE, auth);

    assertEquals(HttpStatus.ACCEPTED, reponse.getStatusCode());
    final InOrder ordre = inOrder(reauthentification, service, journal);
    ordre.verify(reauthentification).exiger(ADMIN, "mdp", "123456");
    ordre.verify(service).demander("3.2.0", "kwaite");
    ordre.verify(journal).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void demander_reauthentificationRefusee_neDeposeRien() {
    doThrow(new ErreurMetier("Mot de passe ou code incorrect.", 422))
        .when(reauthentification)
        .exiger(ADMIN, "mdp", "123456");

    assertThrows(ErreurMetier.class, () -> controller.demander(REQUETE, auth));

    verify(service, never()).demander(anyString(), anyString());
    verify(journal, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void demander_versionRefusee_nEstPasJournaliseeCommeDemandee() {
    doThrow(new ErreurMetier("Cette version n'est pas proposée."))
        .when(service)
        .demander("3.2.0", "kwaite");

    assertThrows(ErreurMetier.class, () -> controller.demander(REQUETE, auth));

    verify(journal, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }
}
