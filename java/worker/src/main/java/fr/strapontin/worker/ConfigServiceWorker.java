/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;
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

  private static final int N = 16384;
  private static final int R = 8;
  private static final int P = 1;
  private static final int DK_LEN = 32;

  private final byte[] cleChiffrement;
  private final NamedParameterJdbcTemplate jdbc;

  /// Crée le service et dérive la clé AES-256 depuis le secret applicatif.
  ///
  /// @param secret secret applicatif (variable {@code APP_CONFIG_SECRET})
  /// @param jdbc   template JDBC Spring
  public ConfigServiceWorker(
      @Value("${app.config.secret}") String secret, NamedParameterJdbcTemplate jdbc) {
    this.cleChiffrement =
        SCrypt.generate(
            secret.getBytes(StandardCharsets.UTF_8),
            "strapontin-app-config".getBytes(StandardCharsets.UTF_8),
            N,
            R,
            P,
            DK_LEN);
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
      return Optional.of(dechiffrer(lignes.get(0)));
    } catch (Exception e) {
      log.error("Échec déchiffrement config '{}' — APP_CONFIG_SECRET modifié ?", cle, e);
      return Optional.empty();
    }
  }

  private String dechiffrer(String chiffre) {
    try {
      byte[] buf = Base64.getDecoder().decode(chiffre);
      byte[] iv = Arrays.copyOf(buf, 12);
      byte[] tag = Arrays.copyOfRange(buf, 12, 28);
      byte[] donnees = Arrays.copyOfRange(buf, 28, buf.length);

      byte[] donneesAvecTag = new byte[donnees.length + 16];
      System.arraycopy(donnees, 0, donneesAvecTag, 0, donnees.length);
      System.arraycopy(tag, 0, donneesAvecTag, donnees.length, 16);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(cleChiffrement, "AES"),
          new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(donneesAvecTag), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Erreur de déchiffrement de configuration", e);
    }
  }
}
