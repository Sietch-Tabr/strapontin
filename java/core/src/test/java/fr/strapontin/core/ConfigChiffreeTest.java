/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link ConfigChiffree}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class ConfigChiffreeTest {

  private static final String SECRET = "mon-secret-de-test-32-chars-ok!!";

  private NamedParameterJdbcTemplate jdbc;
  private ConfigCipher cipher;
  private ConfigChiffree config;

  @BeforeEach
  void preparer() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    cipher = new ConfigCipher(SECRET);
    config = new ConfigChiffree(cipher, new AccesAppConfig(jdbc));
  }

  private void stockerLaValeur(final String chiffree) {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(chiffree));
  }

  @Test
  void lire_cleAbsente_retourneVide() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    assertEquals(Optional.empty(), config.lire("CLE"));
  }

  @Test
  void lire_valeurChiffree_retourneLeClair() {
    stockerLaValeur(cipher.chiffrer("secret-clair"));
    assertEquals(Optional.of("secret-clair"), config.lire("CLE"));
  }

  @Test
  void lire_valeurChiffreeAvecUnAutreSecret_retourneVideSansEchouer() {
    stockerLaValeur(new ConfigCipher("un-autre-secret-de-test-32-chars!!").chiffrer("x"));
    assertEquals(Optional.empty(), config.lire("CLE"));
  }

  @Test
  void ecrire_valeur_ecritLaValeurChiffree() {
    config.ecrire("CLE", "secret-clair");
    final ArgumentCaptor<java.util.Map<String, ?>> parametres =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(jdbc).update(anyString(), parametres.capture());
    final String ecrite = (String) parametres.getValue().get("valeur");
    assertTrue(!ecrite.contains("secret-clair"));
    assertEquals("secret-clair", cipher.dechiffrer(ecrite));
  }

  @Test
  void ecrire_valeurVide_supprimeLaCle() {
    config.ecrire("CLE", " ");
    final ArgumentCaptor<String> requete = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(requete.capture(), anyMap());
    assertTrue(requete.getValue().toUpperCase().contains("DELETE"), requete.getValue());
  }

  @Test
  void ecrire_valeurNull_supprimeLaCle() {
    config.ecrire("CLE", null);
    verify(jdbc).update(org.mockito.ArgumentMatchers.contains("DELETE"), any(java.util.Map.class));
  }
}
