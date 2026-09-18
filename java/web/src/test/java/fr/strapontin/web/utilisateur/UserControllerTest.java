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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Tests unitaires de {@link UserController}.
 *
 * <p>Couvre principalement la garde "dernier admin" sur la suppression de compte, et la
 * révocation multi-sessions au changement de mot de passe.
 *
 * @since 2.2.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UtilisateurRepository utilisateurRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private HttpServletRequest httpRequest;
  @Mock private FindByIndexNameSessionRepository<Session> sessionRepository;

  private UserController controller;

  @BeforeEach
  void setUp() {
    controller = new UserController(utilisateurRepository, passwordEncoder, sessionRepository);
  }

  private Authentication authPour(final String userId, final String role) {
    final UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", role, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void supprimerCompte_adminSeulSurInstance_retourne409() {
    when(utilisateurRepository.compterAdmins()).thenReturn(1);

    final ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("admin-1", "admin"), httpRequest);

    assertEquals(HttpStatus.CONFLICT, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(((Map<?, ?>) rep.getBody()).containsKey("erreur"));
    verify(utilisateurRepository, never()).supprimer(any());
  }

  @Test
  void supprimerCompte_adminAvecAutreAdmin_retourne204() {
    when(utilisateurRepository.compterAdmins()).thenReturn(2);

    final ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("admin-1", "admin"), httpRequest);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(utilisateurRepository).supprimer("admin-1");
  }

  @Test
  void supprimerCompte_utilisateurSimple_retourne204() {
    final ResponseEntity<?> rep =
        controller.supprimerCompte(authPour("user-1", "user"), httpRequest);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(utilisateurRepository).supprimer("user-1");
    verify(utilisateurRepository, never()).compterAdmins();
  }

  // le changement de mot de passe révoque toutes les sessions de l'utilisateur (pas
  // seulement celle de la requête en cours) — sinon une session compromise sur un autre appareil
  // survit jusqu'à expiration naturelle malgré le changement de mot de passe.
  @Test
  void changerMotDePasse_motDePasseActuelCorrect_revoqueToutesLesSessions() {
    when(passwordEncoder.matches("actuel12345678", "")).thenReturn(true);
    when(passwordEncoder.encode("nouveau123456789")).thenReturn("hash-nouveau");
    final Session sessionA = mock(Session.class);
    final Session sessionB = mock(Session.class);
    when(sessionRepository.findByPrincipalName("login"))
        .thenReturn(Map.of("id-a", sessionA, "id-b", sessionB));

    final RequeteMotDePasse requete = new RequeteMotDePasse("actuel12345678", "nouveau123456789");
    final ResponseEntity<Map<String, Object>> rep =
        controller.changerMotDePasse(requete, authPour("user-1", "user"));

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(utilisateurRepository).changerMotDePasse(eq("user-1"), anyString());
    verify(sessionRepository).deleteById("id-a");
    verify(sessionRepository).deleteById("id-b");
  }

  // régression — un second contrôle manuel plus strict que la validation Bean
  // rejetait à tort les identifiants contenant un point ou un tiret, pourtant autorisés par
  // RequeteIdentifiant.
  @Test
  void majIdentifiant_avecPointEtTiret_neRejettePasEtDelegueAuRepository() {
    final RequeteIdentifiant requete = new RequeteIdentifiant("jean.dupont-2");

    final ResponseEntity<Map<String, Object>> rep =
        controller.majIdentifiant(requete, authPour("user-1", "user"));

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(utilisateurRepository).majIdentifiant("user-1", "jean.dupont-2");
  }

  @Test
  void majIdentifiant_dejaUtilise_retourne400() {
    when(utilisateurRepository.identifiantDejaUtilise("bob", "user-1")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> rep =
        controller.majIdentifiant(new RequeteIdentifiant("bob"), authPour("user-1", "user"));

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(utilisateurRepository, never()).majIdentifiant(anyString(), anyString());
  }

  @Test
  void changerMotDePasse_motDePasseActuelIncorrect_neRevoqueAucuneSession() {
    when(passwordEncoder.matches("faux", "")).thenReturn(false);

    final RequeteMotDePasse requete = new RequeteMotDePasse("faux", "nouveau123456789");
    final ResponseEntity<Map<String, Object>> rep =
        controller.changerMotDePasse(requete, authPour("user-1", "user"));

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(sessionRepository, never()).findByPrincipalName(anyString());
    verify(utilisateurRepository, never()).changerMotDePasse(anyString(), anyString());
  }

  @Test
  void changerMotDePasse_champsNull_retourne400() {
    final RequeteMotDePasse requete = new RequeteMotDePasse(null, null);

    final ResponseEntity<Map<String, Object>> rep =
        controller.changerMotDePasse(requete, authPour("user-1", "user"));

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(utilisateurRepository, never()).changerMotDePasse(anyString(), anyString());
  }

  @Test
  void changerMotDePasse_nouveauTropCourt_retourne400() {
    final RequeteMotDePasse requete = new RequeteMotDePasse("actuel12345678", "court");

    final ResponseEntity<Map<String, Object>> rep =
        controller.changerMotDePasse(requete, authPour("user-1", "user"));

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(utilisateurRepository, never()).changerMotDePasse(anyString(), anyString());
  }

  @Test
  void lireProfil_utilisateurExistant_retourne200AvecProfil() {
    when(utilisateurRepository.lireEmails("user-1"))
        .thenReturn(Optional.of(new UtilisateurRepository.EmailsUtilisateur("test@test.fr", null)));

    final ResponseEntity<ProfilUtilisateur> rep = controller.lireProfil(authPour("user-1", "user"));

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals("test@test.fr", rep.getBody().email());
    assertEquals("login", rep.getBody().identifiant());
  }

  @Test
  void lireProfil_utilisateurAbsent_retourne404() {
    when(utilisateurRepository.lireEmails("user-1")).thenReturn(Optional.empty());

    final ResponseEntity<ProfilUtilisateur> rep = controller.lireProfil(authPour("user-1", "user"));

    assertEquals(HttpStatus.NOT_FOUND, rep.getStatusCode());
  }
}
