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

/**
 * Service de lecture de configuration chiffrée pour le worker —
 * déchiffre les valeurs AES-256-GCM de {@code app_config} (même clé
 * SCrypt que le web), lecture seule (écritures côté {@code ConfigService}).
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class ConfigServiceWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigServiceWorker.class);

  private final ConfigCipher cipher;
  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Crée le service et dérive la clé AES-256 depuis le secret applicatif.
   *
   * @param secret secret applicatif (variable {@code APP_CONFIG_SECRET})
   * @param secretPrecedent secret remplacé lors d'une rotation en cours, vide sinon
   * @param jdbc   template JDBC Spring
   */
  public ConfigServiceWorker(
      @Value("${app.config.secret}") final String secret,
      @Value("${app.config.secret-precedent:}") final String secretPrecedent,
      final NamedParameterJdbcTemplate jdbc) {
    this.cipher = new ConfigCipher(secret, secretPrecedent);
    this.jdbc = jdbc;
  }

  /**
   * Lit et déchiffre une valeur depuis {@code app_config}.
   *
   * @param cle la clé de configuration
   * @return la valeur déchiffrée, ou vide si la clé n'existe pas
   */
  public Optional<String> lireConfig(final String cle) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    final List<String> lignes =
        jdbc.query(
            "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
            Map.of("cle", cle),
            (rs, _) -> rs.getString("valeur_chiffree"));
    if (lignes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(cipher.dechiffrer(lignes.get(0)));
    } catch (Exception e) {
      LOG.error("Échec déchiffrement config '{}' — APP_CONFIG_SECRET modifié ?", cle, e);
      return Optional.empty();
    }
  }
}
