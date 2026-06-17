/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/// Tests unitaires de {@link UserController}.
///
/// <p>Couvre principalement la garde "dernier admin" sur la suppression de compte.
///
/// @since 2.2.6
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UtilisateurRepository utilisateurRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private HttpServletRequest httpRequest;

  private UserController controller;

  @BeforeEach
  void setUp() {
    controller = new UserController(utilisateurRepository, passwordEncoder);
  }

  private Authentication authPour(String userId, String role) {
    UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", role, false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void supprimerCompte_adminSeulSurInstance_retourne409() {
    when(utilisateurRepository.compterAdmins()).thenReturn(1);

    ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("admin-1", "admin"), httpRequest);

    assertEquals(HttpStatus.CONFLICT, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(((Map<?, ?>) rep.getBody()).containsKey("erreur"));
    verify(utilisateurRepository, never()).supprimer(any());
  }

  @Test
  void supprimerCompte_adminAvecAutreAdmin_retourne204() {
    when(utilisateurRepository.compterAdmins()).thenReturn(2);

    ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("admin-1", "admin"), httpRequest);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(utilisateurRepository).supprimer("admin-1");
  }

  @Test
  void supprimerCompte_utilisateurSimple_retourne204() {
    ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("user-1", "user"), httpRequest);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(utilisateurRepository).supprimer("user-1");
    verify(utilisateurRepository, never()).compterAdmins();
  }
}
