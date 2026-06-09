/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/// Tests unitaires pour {@link AuthService}.
///
/// @since 2.2.0
/// @author Kwaite
class AuthServiceTest {

  private VerificationRepository verificationRepository;
  private AuthService authService;

  @BeforeEach
  void setUp() {
    verificationRepository = mock(VerificationRepository.class);
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.encode(any())).thenReturn("$hash$");
    authService = new AuthService(verificationRepository, mock(EmailService.class), passwordEncoder);
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
}
