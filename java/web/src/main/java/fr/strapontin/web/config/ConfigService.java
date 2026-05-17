/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de configuration chiffrée (portage de `config.ts`).
///
/// La clé AES-256 est dérivée de `APP_CONFIG_SECRET` via SCrypt
/// (paramètres par défaut Node.js : N=16 384, r=8, p=1, dkLen=32).
///
/// Format de stockage : `base64(IV(12) || TAG(16) || données_chiffrées)`.
/// Compatible avec les valeurs chiffrées par le module TypeScript.
///
/// Les valeurs sont mises en cache Caffeine 5 minutes.
/// [sauvegarderConfig] invalide immédiatement le cache de la clé modifiée.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class ConfigService {

  private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
  private static final SecureRandom rng = new SecureRandom();

  // Paramètres scrypt pour la dérivation de clé AES (Node.js crypto.scryptSync defaults)
  // DIFFÉRENT de BetterAuthPasswordEncoder : r=8 (Node.js default), r=16 (Better-Auth)
  private static final int N = 16384;
  private static final int R = 8;
  private static final int P = 1;
  private static final int DK_LEN = 32;

  private final byte[] cleChiffrement;
  private final NamedParameterJdbcTemplate jdbc;
  private final Cache<String, Optional<String>> cache;

  public ConfigService(
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
    this.cache =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(200).build();
  }

  /// Lit une valeur de configuration depuis le cache ou la base de données.
  ///
  /// @param cle la clé de configuration
  /// @return la valeur déchiffrée, ou vide si la clé n'existe pas
  public Optional<String> lireConfig(String cle) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    return cache.get(cle, this::chargerDepuisDb);
  }

  /// Sauvegarde une valeur de configuration chiffrée en base de données.
  ///
  /// Si `valeur` est vide, la clé est supprimée.
  ///
  /// @param cle   la clé de configuration
  /// @param valeur la valeur en clair, ou `null` pour supprimer
  public void sauvegarderConfig(String cle, String valeur) {
    Objects.requireNonNull(cle, "La clé de configuration est requise");
    cache.invalidate(cle);
    if (valeur == null || valeur.isBlank()) {
      jdbc.update("DELETE FROM app_config WHERE cle = :cle", Map.of("cle", cle));
      return;
    }
    String valeurChiffree = chiffrer(valeur);
    String sql =
        """
        INSERT INTO app_config (cle, valeur_chiffree)
        VALUES (:cle, :valeur)
        ON CONFLICT (cle) DO UPDATE
          SET valeur_chiffree = EXCLUDED.valeur_chiffree,
              mis_a_jour_le   = now()
        """;
    jdbc.update(sql, Map.of("cle", cle, "valeur", valeurChiffree));
  }

  /// Retourne l'ensemble des clés présentes en base de données.
  ///
  /// @return les clés configurées
  public Set<String> lireConfigsPresentes() {
    List<String> cles =
        jdbc.query("SELECT cle FROM app_config", Map.of(), (rs, _) -> rs.getString("cle"));
    return new HashSet<>(cles);
  }

  private Optional<String> chargerDepuisDb(String cle) {
    List<String> lignes =
        jdbc.query(
            "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
            Map.of("cle", cle),
            (rs, _) -> rs.getString("valeur_chiffree"));
    if (lignes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(dechiffrer(lignes.get(0)));
    } catch (Exception e) {
      log.error("Échec déchiffrement config '{}' — APP_CONFIG_SECRET modifié ?", cle, e);
      return Optional.empty();
    }
  }

  /// Chiffre une valeur en AES-256-GCM.
  ///
  /// Format de sortie : `base64(IV(12) || TAG(16) || données_chiffrées)`
  private String chiffrer(String valeur) {
    try {
      byte[] iv = new byte[12];
      rng.nextBytes(iv);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(cleChiffrement, "AES"),
          new GCMParameterSpec(128, iv));
      // doFinal retourne : données_chiffrées || TAG(16)
      byte[] resultat = cipher.doFinal(valeur.getBytes(StandardCharsets.UTF_8));
      int donneesLen = resultat.length - 16;

      // Réordonne en IV(12) || TAG(16) || données_chiffrées pour correspondre au format Node.js
      byte[] stocke = new byte[12 + 16 + donneesLen];
      System.arraycopy(iv, 0, stocke, 0, 12);
      System.arraycopy(resultat, donneesLen, stocke, 12, 16);
      System.arraycopy(resultat, 0, stocke, 28, donneesLen);

      return Base64.getEncoder().encodeToString(stocke);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Erreur de chiffrement de configuration", e);
    }
  }

  /// Déchiffre une valeur AES-256-GCM.
  ///
  /// Format attendu : `base64(IV(12) || TAG(16) || données_chiffrées)`
  private String dechiffrer(String chiffre) {
    try {
      byte[] buf = Base64.getDecoder().decode(chiffre);
      byte[] iv = Arrays.copyOf(buf, 12);
      byte[] tag = Arrays.copyOfRange(buf, 12, 28);
      byte[] donnees = Arrays.copyOfRange(buf, 28, buf.length);

      // AES/GCM attend données_chiffrées || TAG
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
