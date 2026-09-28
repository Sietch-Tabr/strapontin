/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link UtilisateurRepository}.
 *
 * <p>{@code compterNouveauxMatchesArtistes} extrait de {@code securite.SessionController} .
 *
 * @since 2.7.0
 * @author Kwaite
 */
class UtilisateurRepositoryTest {

  private static final String SECRET_CONFIG = "secret-de-test-utilisateur-repository";

  private NamedParameterJdbcTemplate jdbc;
  private UtilisateurRepository repository;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    repository = new UtilisateurRepository(jdbc, SECRET_CONFIG, "");
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

  @Test
  void activerTotp_secretFourni_stockeValeurChiffreeDifferenteDuSecret() {
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

    repository.activerTotp("user-1", "JBSWY3DPEHPK3PXP");

    verify(jdbc).update(anyString(), params.capture());
    assertEquals(false, params.getValue().get("secret").equals("JBSWY3DPEHPK3PXP"));
  }

  @Test
  void lireSecretTotp_valeurChiffree_retourneSecretDechiffre() {
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    repository.activerTotp("user-1", "JBSWY3DPEHPK3PXP");
    verify(jdbc).update(anyString(), params.capture());
    final String chiffre = (String) params.getValue().get("secret");

    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(chiffre));

    assertEquals("JBSWY3DPEHPK3PXP", repository.lireSecretTotp("user-1"));
  }

  @Test
  void lireSecretTotp_valeurLegacyEnClair_retourneValeurBruteSansEchouer() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("JBSWY3DPEHPK3PXP"));

    assertEquals("JBSWY3DPEHPK3PXP", repository.lireSecretTotp("user-1"));
  }

  @Test
  void lireSecretTotp_aucunSecret_retourneNull() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    assertNull(repository.lireSecretTotp("user-1"));
  }

  @Test
  void lireEchecsTotp_valeurPresente_retourneLeCompte() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(3);
    assertEquals(3, repository.lireEchecsTotp("user-1"));
  }

  @Test
  void lireEchecsTotp_valeurNull_retourneZero() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(null);
    assertEquals(0, repository.lireEchecsTotp("user-1"));
  }
}
