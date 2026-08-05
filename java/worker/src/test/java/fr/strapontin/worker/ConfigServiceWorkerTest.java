/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.core.ConfigCipher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link ConfigServiceWorker}.
///
/// <p>Le module n'avait aucun test dédié avant (extraction de {@code ConfigCipher}) — le
/// chiffrement/déchiffrement lui-même est testé dans {@code core.ConfigCipherTest}, ce fichier
/// couvre le comportement propre au service worker : lecture seule, résilience au mauvais secret.
///
/// @since 2.7.0
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class ConfigServiceWorkerTest {

  private static final String SECRET = "mon-secret-de-test-32-chars-ok!!";

  @Mock private NamedParameterJdbcTemplate jdbc;

  private ConfigServiceWorker creerService() {
    return new ConfigServiceWorker(SECRET, jdbc);
  }

  @Test
  void lireConfig_cleAbsenteEnBase_retourneVide() {
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of());
    assertEquals(Optional.empty(), creerService().lireConfig("CLE_INEXISTANTE"));
  }

  @Test
  void lireConfig_valeurChiffreeParLeMemeSecret_retourneValeurEnClair() {
    ConfigCipher cipher = new ConfigCipher(SECRET);
    String chiffre = cipher.chiffrer("valeur-en-clair");
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of(chiffre));

    assertEquals(Optional.of("valeur-en-clair"), creerService().lireConfig("MA_CLE"));
  }

  @Test
  void lireConfig_valeurChiffreeParUnAutreSecret_neLancePasEtRetourneVide() {
    // Même garde-fou que web.ConfigService : un APP_CONFIG_SECRET différent ne doit jamais faire
    // planter le worker, seulement logger l'échec et traiter la clé comme absente.
    ConfigCipher autreCipher = new ConfigCipher("autre-secret-32-caracteres-ok!!");
    String chiffreAvecAutreSecret = autreCipher.chiffrer("peu importe");
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of(chiffreAvecAutreSecret));

    assertEquals(Optional.empty(), creerService().lireConfig("CLE_AVEC_MAUVAIS_SECRET"));
  }
}
