/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link SalleController}.
 *
 * <p>Vérifie la délégation à {@link SalleService} et la validation manuelle du contrôleur
 * (nom vide/trop long, conversions blank→null des identifiants externes). Les tests de
 * {@code signalerManquante} vivent dans {@link SalleSignalementControllerTest}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class SalleControllerTest {

  @Mock private SalleService salleService;
  @Mock private EmailService emailService;

  private SalleController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new SalleController(salleService, emailService);
    final UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void listerSalles_delegueAuServiceAvecUserIdEtQ() {
    final ReponseSalles attendu = new ReponseSalles(List.of(), List.of(), false);
    when(salleService.lister("user-1", "zenith")).thenReturn(attendu);

    final ResponseEntity<ReponseSalles> rep = controller.listerSalles("zenith", auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals(attendu, rep.getBody());
  }

  @Test
  void ajouterSalle_nomValide_retourne201EtDelegueAuService() {
    final ResponseEntity<Map<String, Object>> rep =
        controller.ajouterSalle(new RequeteAjouter("Olympia", "tm-123"), auth);

    assertEquals(HttpStatus.CREATED, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("succes"));
    verify(salleService).ajouter("user-1", "Olympia", "tm-123", null, null);
  }

  @Test
  void ajouterSalle_nomVide_retourne400SansDelegation() {
    final ResponseEntity<Map<String, Object>> rep =
        controller.ajouterSalle(new RequeteAjouter("   ", null), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(salleService, never()).ajouter(anyString(), anyString(), any(), any(), any());
  }

  @Test
  void ajouterSalle_nomTropLong_retourne400() {
    final ResponseEntity<Map<String, Object>> rep =
        controller.ajouterSalle(new RequeteAjouter("a".repeat(201), null), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
  }

  @Test
  void ajouterSalle_identifiantsExternesBlancs_convertisEnNull() {
    controller.ajouterSalle(new RequeteAjouter("Olympia", "  ", "", "  "), auth);

    verify(salleService).ajouter("user-1", "Olympia", null, null, null);
  }

  @Test
  void ajouterSalle_identifiantsExternesPresents_sontStrippes() {
    controller.ajouterSalle(new RequeteAjouter("Olympia", " tm-123 ", " osm-1 ", "Q42"), auth);

    verify(salleService).ajouter("user-1", "Olympia", "tm-123", "osm-1", "Q42");
  }

  @Test
  void supprimerSalle_delegueAuServiceEtRetourne204() {
    final UUID salleId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    final ResponseEntity<Void> rep = controller.supprimerSalle(salleId, auth);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(salleService).supprimer("user-1", salleId);
  }
}
