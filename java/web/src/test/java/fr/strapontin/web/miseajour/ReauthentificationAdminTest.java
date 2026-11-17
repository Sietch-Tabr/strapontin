/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.TotpService;
import fr.strapontin.web.securite.UtilisateurDetails;
import fr.strapontin.web.utilisateur.UserController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Tests unitaires pour {@link ReauthentificationAdmin}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class ReauthentificationAdminTest {

  private PasswordEncoder encodeur;
  private TotpService totp;
  private UserController utilisateurs;
  private LogAdminService journal;
  private ReauthentificationAdmin reauthentification;

  private static UtilisateurDetails admin(final boolean totpActif) {
    return new UtilisateurDetails(
        "id-1", "kwaite", "k@exemple.fr", "hash", "admin", totpActif, false, false);
  }

  @BeforeEach
  void preparer() {
    encodeur = mock(PasswordEncoder.class);
    totp = mock(TotpService.class);
    utilisateurs = mock(UserController.class);
    journal = mock(LogAdminService.class);
    reauthentification = new ReauthentificationAdmin(encodeur, totp, utilisateurs, journal);
    when(utilisateurs.lireSecretTotp("id-1")).thenReturn("SECRET");
    when(utilisateurs.deuxFacteursActif("id-1")).thenReturn(true);
  }

  @Test
  void exiger_motDePasseEtCodeValides_accepte() {
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    when(totp.verifierCode("SECRET", "123456")).thenReturn(true);
    assertDoesNotThrow(() -> reauthentification.exiger(admin(true), "mdp", "123456"));
  }

  @Test
  void exiger_totpNonActive_refuseEn422SansVerifier() {
    when(utilisateurs.deuxFacteursActif("id-1")).thenReturn(false);
    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> reauthentification.exiger(admin(false), "mdp", "123456"));
    assertEquals(422, erreur.getStatus());
    verify(encodeur, never()).matches(anyString(), anyString());
  }

  @Test
  void exiger_motDePasseFaux_refuseEnJournalisant() {
    when(totp.verifierCode("SECRET", "123456")).thenReturn(true);
    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> reauthentification.exiger(admin(true), "faux", "123456"));
    assertEquals(422, erreur.getStatus());
    verify(journal).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void exiger_codeFaux_refuseSansDireLequelEstFaux() {
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> reauthentification.exiger(admin(true), "mdp", "000000"));
    assertEquals("Mot de passe ou code incorrect.", erreur.getMessage());
  }

  @Test
  void exiger_secretTotpAbsent_refuse() {
    when(utilisateurs.lireSecretTotp("id-1")).thenReturn(null);
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    assertThrows(ErreurMetier.class, () -> reauthentification.exiger(admin(true), "mdp", "123456"));
  }

  @Test
  void exiger_cinqEchecs_bloqueMemeAvecLesBonnesValeurs() {
    for (int i = 0; i < 5; i++) {
      assertThrows(
          ErreurMetier.class, () -> reauthentification.exiger(admin(true), "faux", "000000"));
    }
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    when(totp.verifierCode("SECRET", "123456")).thenReturn(true);

    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> reauthentification.exiger(admin(true), "mdp", "123456"));
    assertEquals(429, erreur.getStatus());
  }

  @Test
  void exiger_succesApresEchecs_remetLeCompteurAZero() {
    for (int i = 0; i < 4; i++) {
      assertThrows(
          ErreurMetier.class, () -> reauthentification.exiger(admin(true), "faux", "000000"));
    }
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    when(totp.verifierCode("SECRET", "123456")).thenReturn(true);
    reauthentification.exiger(admin(true), "mdp", "123456");

    assertThrows(
        ErreurMetier.class, () -> reauthentification.exiger(admin(true), "faux", "000000"));
    assertDoesNotThrow(() -> reauthentification.exiger(admin(true), "mdp", "123456"));
  }

  @Test
  void exiger_totpActiveDepuisLaConnexion_estAccepte() {
    // Le principal de la session est figé à la connexion : seule la base fait foi.
    when(encodeur.matches("mdp", "hash")).thenReturn(true);
    when(totp.verifierCode("SECRET", "123456")).thenReturn(true);

    assertDoesNotThrow(() -> reauthentification.exiger(admin(false), "mdp", "123456"));
  }

  @Test
  void exiger_totpDesactiveDepuisLaConnexion_estRefuse() {
    when(utilisateurs.deuxFacteursActif("id-1")).thenReturn(false);

    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> reauthentification.exiger(admin(true), "mdp", "123456"));
    assertEquals(422, erreur.getStatus());
  }

  @Test
  void totpActif_litLaBaseEtNonLaSession() {
    assertTrue(reauthentification.totpActif(admin(false)));
    when(utilisateurs.deuxFacteursActif("id-1")).thenReturn(false);
    assertFalse(reauthentification.totpActif(admin(true)));
  }
}
