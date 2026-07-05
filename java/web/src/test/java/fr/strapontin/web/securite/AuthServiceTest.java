/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/// Tests unitaires pour {@link AuthService}.
///
/// @since 2.2.0
/// @author Kwaite
class AuthServiceTest {

  private VerificationRepository verificationRepository;
  private FindByIndexNameSessionRepository<Session> sessionRepository;
  private AuthService authService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    verificationRepository = mock(VerificationRepository.class);
    sessionRepository = mock(FindByIndexNameSessionRepository.class);
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.encode(any())).thenReturn("$hash$");
    authService =
        new AuthService(
            verificationRepository, mock(EmailService.class), passwordEncoder, sessionRepository);
  }

  @Test
  void reinitialiserMotDePasse_tokenInvite_conserveMustChangeCredentials() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("invite:alice@exemple.fr"));

    authService.reinitialiserMotDePasse("token", "nouveauMotDePasse12");

    verify(verificationRepository, never()).majMustChangeFalseParEmail(any());
    verify(verificationRepository).majMotDePasseParEmail(eq("alice@exemple.fr"), any());
  }

  @Test
  void reinitialiserMotDePasse_tokenReset_metMustChangeFalse() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("reset:alice@exemple.fr"));

    authService.reinitialiserMotDePasse("token", "nouveauMotDePasse12");

    verify(verificationRepository).majMustChangeFalseParEmail("alice@exemple.fr");
    verify(verificationRepository).majMotDePasseParEmail(eq("alice@exemple.fr"), any());
  }

  @Test
  void reinitialiserMotDePasse_tokenReset_revoqueSessionsUtilisateur() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("reset:alice@exemple.fr"));
    when(verificationRepository.lireUsernameParEmail("alice@exemple.fr"))
        .thenReturn(Optional.of("alice"));
    Session session = mock(Session.class);
    when(sessionRepository.findByPrincipalName("alice"))
        .thenReturn(Map.of("session-id-1", session));

    authService.reinitialiserMotDePasse("token", "nouveauMotDePasse12");

    verify(sessionRepository).deleteById("session-id-1");
  }

  @Test
  void reinitialiserMotDePasse_tokenReset_sansUsername_neLancePasException() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("reset:alice@exemple.fr"));
    when(verificationRepository.lireUsernameParEmail("alice@exemple.fr"))
        .thenReturn(Optional.empty());

    authService.reinitialiserMotDePasse("token", "nouveauMotDePasse12");

    verify(sessionRepository, never()).findByPrincipalName(any());
    verify(sessionRepository, never()).deleteById(any());
  }

  @Test
  void activerCompteInvite_identifiantPrisEnRace_leveErreurMetierAuLieuDe500() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("invite:alice@exemple.fr"));
    when(verificationRepository.identifiantDejaUtiliseParEmail("bob", "alice@exemple.fr"))
        .thenReturn(false);
    doThrow(new DataIntegrityViolationException("contrainte unique violée"))
        .when(verificationRepository)
        .majUsernameEtActiverParEmail("alice@exemple.fr", "bob");

    assertThatThrownBy(() -> authService.activerCompteInvite("token", "bob", "motDePasse12"))
        .isInstanceOf(ErreurMetier.class)
        .hasMessage("Cet identifiant est déjà utilisé.");
  }

  @Test
  void activerCompteInvite_identifiantLibre_activeLeCompte() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("invite:alice@exemple.fr"));
    when(verificationRepository.identifiantDejaUtiliseParEmail("bob", "alice@exemple.fr"))
        .thenReturn(false);

    String identifiant = authService.activerCompteInvite("token", "bob", "motDePasse12");

    assertThat(identifiant).isEqualTo("bob");
    verify(verificationRepository).majUsernameEtActiverParEmail("alice@exemple.fr", "bob");
    verify(verificationRepository).supprimerParTokenHash(any());
  }
}
