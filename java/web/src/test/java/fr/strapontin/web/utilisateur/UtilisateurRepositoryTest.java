/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link UtilisateurRepository}.
///
/// <p>{@code compterNouveauxMatchesArtistes} extrait de {@code securite.SessionController}
///.
///
/// @since 2.7.0
/// @author Kwaite
class UtilisateurRepositoryTest {

  private NamedParameterJdbcTemplate jdbc;
  private UtilisateurRepository repository;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    repository = new UtilisateurRepository(jdbc);
  }

  @Test
  void compterNouveauxMatchesArtistes_compteValide_retourneLeCompte() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(5);
    assertEquals(5, repository.compterNouveauxMatchesArtistes("user-1"));
  }

  @Test
  void compterNouveauxMatchesArtistes_compteNull_retourneZero() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(null);
    assertEquals(0, repository.compterNouveauxMatchesArtistes("user-1"));
  }
}
