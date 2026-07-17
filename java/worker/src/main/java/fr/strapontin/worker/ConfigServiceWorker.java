/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConfigCipher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de lecture de configuration chiffrée pour le worker.
///
/// Déchiffre les valeurs AES-256-GCM stockées dans `app_config`,
/// en utilisant la même clé dérivée via SCrypt que le module web.
///
/// Ce service est en lecture seule : les écritures de configuration sont
/// effectuées par le module web via son propre `ConfigService`.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class ConfigServiceWorker {

  private static final Logger log = LoggerFactory.getLogger(ConfigServiceWorker.class);

  private final ConfigCipher cipher;
  private final NamedParameterJdbcTemplate jdbc;

  /// Crée le service et dérive la clé AES-256 depuis le secret applicatif.
  ///
  /// @param secret secret applicatif (variable {@code APP_CONFIG_SECRET})
  /// @param jdbc   template JDBC Spring
  public ConfigServiceWorker(
      @Value("${app.config.secret}") String secret, NamedParameterJdbcTemplate jdbc) {
    this.cipher = new ConfigCipher(secret);
    this.jdbc = jdbc;
  }

  /// Lit et déchiffre une valeur depuis {@code app_config}.
  ///
  /// @param cle la clé de configuration
  /// @return la valeur déchiffrée, ou vide si la clé n'existe pas
  public Optional<String> lireConfig(String cle) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    List<String> lignes =
        jdbc.query(
            "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
            Map.of("cle", cle),
            (rs, _) -> rs.getString("valeur_chiffree"));
    if (lignes.isEmpty()) return Optional.empty();
    try {
      return Optional.of(cipher.dechiffrer(lignes.get(0)));
    } catch (Exception e) {
      log.error("Échec déchiffrement config '{}' — APP_CONFIG_SECRET modifié ?", cle, e);
      return Optional.empty();
    }
  }
}
