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
import fr.strapontin.web.invitation.InvitationUtilisateurService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Tests unitaires pour {@link AuthService}.
 *
 * @since 2.2.0
 * @author Kwaite
 */
class AuthServiceTest {

  private VerificationRepository verificationRepository;
  private FindByIndexNameSessionRepository<Session> sessionRepository;
  private EmailService emailService;
  private AuthService authService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    verificationRepository = mock(VerificationRepository.class);
    sessionRepository = mock(FindByIndexNameSessionRepository.class);
    emailService = mock(EmailService.class);
    final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.encode(any())).thenReturn("$hash$");
    final InvitationUtilisateurService invitations = mock(InvitationUtilisateurService.class);
    when(invitations.activer(any(), any(), any())).thenReturn(Optional.empty());
    authService =
        new AuthService(
            verificationRepository, emailService, passwordEncoder, sessionRepository, invitations);
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
    final Session session = mock(Session.class);
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

    final String identifiant = authService.activerCompteInvite("token", "bob", "motDePasse12");

    assertThat(identifiant).isEqualTo("bob");
    verify(verificationRepository).majUsernameEtActiverParEmail("alice@exemple.fr", "bob");
    verify(verificationRepository).supprimerParTokenHash(any());
  }

  @Test
  void motDePasseOublie_emailInconnu_neFaitRienSansErreur_antiEnumeration() {
    when(verificationRepository.utilisateurExistePourEmail("inconnu@exemple.fr")).thenReturn(false);

    authService.motDePasseOublie("inconnu@exemple.fr", "https://strapontin.fr");

    verify(verificationRepository, never()).inserer(any(), any(), any(), any());
    verify(emailService, never()).envoyerResetMotDePasse(any(), any());
  }

  @Test
  void motDePasseOublie_emailConnu_genereUnTokenExpirantDansUneHeure() {
    when(verificationRepository.utilisateurExistePourEmail("alice@exemple.fr")).thenReturn(true);

    authService.motDePasseOublie("alice@exemple.fr", "https://strapontin.fr");

    verify(verificationRepository).supprimerParIdentifiant("reset:alice@exemple.fr");
    final ArgumentCaptor<Instant> expiration = ArgumentCaptor.forClass(Instant.class);
    verify(verificationRepository)
        .inserer(any(), eq("reset:alice@exemple.fr"), any(), expiration.capture());
    final long minutesAvantExpiration =
        Duration.between(Instant.now(), expiration.getValue()).toMinutes();
    assertThat(minutesAvantExpiration).isBetween(58L, 60L);
    verify(emailService).envoyerResetMotDePasse(eq("alice@exemple.fr"), any());
  }

  @Test
  void estTokenInvite_tokenInvitation_retourneVrai() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("invite:alice@exemple.fr"));

    assertThat(authService.estTokenInvite("token")).isTrue();
  }

  @Test
  void estTokenInvite_tokenResetClassique_retourneFaux() {
    when(verificationRepository.lireIdentifiantParTokenHash(any()))
        .thenReturn(Optional.of("reset:alice@exemple.fr"));

    assertThat(authService.estTokenInvite("token")).isFalse();
  }

  @Test
  void estTokenInvite_tokenInconnu_retourneFaux() {
    when(verificationRepository.lireIdentifiantParTokenHash(any())).thenReturn(Optional.empty());

    assertThat(authService.estTokenInvite("token-inconnu")).isFalse();
  }
}
