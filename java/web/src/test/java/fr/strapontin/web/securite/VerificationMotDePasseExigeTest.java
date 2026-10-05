/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.CredentialsExpiredException;

/**
 * Tests unitaires de {@link VerificationMotDePasseExige}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class VerificationMotDePasseExigeTest {

  private static final UtilisateurDetails UTILISATEUR =
      new UtilisateurDetails("u1", "login", "u1@test.fr", "hash", "user", false, false, false);

  private NamedParameterJdbcTemplate jdbc;
  private VerificationMotDePasseExige verification;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    verification = new VerificationMotDePasseExige(jdbc);
  }

  @Test
  void check_nouveauMotDePasseExige_refuseLaConnexion() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Boolean.class))).thenReturn(List.of(true));

    assertThrows(CredentialsExpiredException.class, () -> verification.check(UTILISATEUR));
  }

  @Test
  void check_aucuneExigence_laissePasser() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Boolean.class))).thenReturn(List.of(false));

    assertDoesNotThrow(() -> verification.check(UTILISATEUR));
  }
}
