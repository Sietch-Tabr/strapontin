/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.core.ConfigCipher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link ConfigService}.
 *
 * <p>Le chiffrement/déchiffrement AES-256-GCM lui-même est testé dans
 * {@code fr.strapontin.core.ConfigCipherTest} — ce fichier se concentre sur le
 * comportement propre au service : cache, sauvegarde/suppression, résilience au mauvais secret.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

  private static final String SECRET = "mon-secret-de-test-32-chars-ok!!";

  @Mock private NamedParameterJdbcTemplate jdbc;

  private ConfigService creerService() {
    return new ConfigService(SECRET, jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sauvegarderConfig_valeurNonVide_persisteValeurChiffreeEtNonEnClair() {
    ConfigService service = creerService();
    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

    service.sauvegarderConfig("MA_CLE", "valeur-en-clair");

    verify(jdbc).update(anyString(), params.capture());
    Object valeurStockee = params.getValue().get("valeur");
    assertNotEquals("valeur-en-clair", valeurStockee);
  }

  @Test
  void sauvegarderConfig_valeurVide_supprimeLaCle() {
    ConfigService service = creerService();
    service.sauvegarderConfig("MA_CLE", "");
    verify(jdbc).update(contains("DELETE FROM app_config"), anyMap());
  }

  @Test
  void lireConfig_cleAbsenteEnBase_retourneVide() {
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of());
    ConfigService service = creerService();
    assertEquals(Optional.empty(), service.lireConfig("CLE_INEXISTANTE"));
  }

  @Test
  void lireConfig_valeurChiffreeParUnAutreSecret_neLancePasEtRetourneVide() {
    // Simule une valeur chiffrée avec un secret différent (ex. APP_CONFIG_SECRET changé) : le
    // service doit logger l'échec et retourner Optional.empty(), jamais propager l'exception.
    ConfigCipher autreCipher = new ConfigCipher("autre-secret-32-caracteres-ok!!");
    String chiffreAvecAutreSecret = autreCipher.chiffrer("peu importe");
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of(chiffreAvecAutreSecret));

    ConfigService service = creerService();
    assertEquals(Optional.empty(), service.lireConfig("CLE_AVEC_MAUVAIS_SECRET"));
  }

  @Test
  void lireConfig_appelsRepetes_utiliseLeCache() {
    when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of());
    ConfigService service = creerService();

    service.lireConfig("CLE_INEXISTANTE");
    service.lireConfig("CLE_INEXISTANTE");

    // Un seul appel DB malgré deux lectures — la seconde est servie par le cache Caffeine.
    verify(jdbc, times(1)).query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<String>>any());
  }
}
