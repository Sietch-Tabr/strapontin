/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.AuthService;
import fr.strapontin.web.securite.PasswordResetController;
import fr.strapontin.web.securite.RegleIdentifiant;
import fr.strapontin.web.securite.ResetPasswordRequest;
import fr.strapontin.web.securite.UtilisateurDetails;
import fr.strapontin.web.setup.RequeteSetup;
import fr.strapontin.web.setup.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Premier démarrage, activation d'invitation et changement d'identifiant
 * appliquent la même règle, avec le même message : une divergence entre
 * parcours fait échouer ce test.
 * @since 3.0.3
 * @author Kwaite
 */
class RegleIdentifiantParcoursTest {

  private static List<Consumer<String>> parcours() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    final SetupService setup = new SetupService(jdbc, mock(ConfigService.class));
    setup.setSelf(setup);

    final PasswordResetController invitation =
        new PasswordResetController(
            mock(AuthenticationManager.class),
            mock(AuthService.class),
            mock(ConfigService.class),
            mock(LogAdminService.class));

    final UserController changement =
        new UserController(
            mock(UtilisateurRepository.class),
            mock(PasswordEncoder.class),
            mock(FindByIndexNameSessionRepository.class));
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal())
        .thenReturn(
            new UtilisateurDetails("u1", "login", "u1@test.fr", "", "user", false, false, false));

    return List.of(
        identifiant ->
            setup.initialiser(
                new RequeteSetup(identifiant, "admin@exemple.fr", "motdepasse12345", null, null)),
        identifiant ->
            invitation.reinitialiserMotDePasse(
                new ResetPasswordRequest("token", "motdepasse12345", identifiant),
                mock(HttpServletRequest.class)),
        identifiant -> changement.majIdentifiant(new RequeteIdentifiant(identifiant), auth));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxyz12345", "jean dupont", "été!", "a:b"})
  void parcours_identifiantInvalide_memeRefusEtMemeMessage(final String identifiant) {
    for (final Consumer<String> appel : parcours()) {
      final ErreurMetier erreur = assertThrows(ErreurMetier.class, () -> appel.accept(identifiant));
      assertEquals(RegleIdentifiant.MESSAGE, erreur.getMessage());
    }
  }

  @Test
  void valider_majusculesEtEspacesAutour_normalise() {
    assertEquals("jean.dupont-2", RegleIdentifiant.valider("  Jean.Dupont-2 "));
  }

  @Test
  void valider_trenteCaracteres_accepte() {
    assertEquals("a".repeat(30), RegleIdentifiant.valider("a".repeat(30)));
  }
}
