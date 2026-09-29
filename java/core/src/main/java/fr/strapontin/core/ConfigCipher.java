/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;

/**
 * Chiffrement/déchiffrement AES-256-GCM de {@code app_config}
 * (détail : CLAUDE.md, Configuration applicative).
 * @since 2.7.0
 * @author Kwaite
 */
public final class ConfigCipher {

  private static final SecureRandom RNG = new SecureRandom();

  private static final int SCRYPT_N = 16_384;
  private static final int SCRYPT_R = 8;
  private static final int SCRYPT_P = 1;
  private static final int DK_LEN = 32;

  private final byte[] cleChiffrement;
  // Clé du secret remplacé lors d'une rotation, tant que toutes les valeurs ne
  // sont pas rechiffrées : null hors rotation.
  private final ConfigCipher precedent;

  /**
   * Dérive la clé AES-256 depuis le secret applicatif.
   *
   * @param secret secret applicatif (variable {@code APP_CONFIG_SECRET})
   */
  // Le sel SCrypt est volontairement fixe (pas une clé de chiffrement secrète)
  // : la clé réelle dérive de "secret" (APP_CONFIG_SECRET), jamais codé en dur
  // — changer ce sel rendrait illisibles toutes les valeurs app_config déjà
  // chiffrées, au même titre qu'un changement d'APP_CONFIG_SECRET.
  public ConfigCipher(final String secret) {
    this(secret, null);
  }

  /**
   * Comme {@link #ConfigCipher(String)}, en acceptant aussi au déchiffrement
   * les valeurs encore chiffrées avec le secret précédent (rotation en cours) —
   * le chiffrement utilise toujours le secret courant.
   *
   * @param secret secret applicatif courant
   * @param secretPrecedent secret remplacé, ou null/vide hors rotation
   */
  @SuppressWarnings("PMD.HardCodedCryptoKey")
  public ConfigCipher(final String secret, final String secretPrecedent) {
    Objects.requireNonNull(secret, "secret");
    this.precedent =
        secretPrecedent == null || secretPrecedent.isBlank() || secretPrecedent.equals(secret)
            ? null
            : new ConfigCipher(secretPrecedent);
    this.cleChiffrement =
        SCrypt.generate(
            secret.getBytes(StandardCharsets.UTF_8),
            "strapontin-app-config".getBytes(StandardCharsets.UTF_8),
            SCRYPT_N,
            SCRYPT_R,
            SCRYPT_P,
            DK_LEN);
  }

  /**
   * Chiffre une valeur en AES-256-GCM.
   *
   * <p>Format de sortie : {@code base64(IV(12) || TAG(16) || données_chiffrées)}
   *
   * @param valeur valeur en clair
   * @return valeur chiffrée encodée en base64
   */
  public String chiffrer(final String valeur) {
    try {
      final byte[] iv = new byte[12];
      RNG.nextBytes(iv);

      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(cleChiffrement, "AES"),
          new GCMParameterSpec(128, iv));
      // doFinal retourne : données_chiffrées || TAG(16)
      final byte[] resultat = cipher.doFinal(valeur.getBytes(StandardCharsets.UTF_8));
      final int donneesLen = resultat.length - 16;

      // Réordonne en IV(12) || TAG(16) || données_chiffrées pour correspondre
      // au format Node.js
      final byte[] stocke = new byte[12 + 16 + donneesLen];
      System.arraycopy(iv, 0, stocke, 0, 12);
      System.arraycopy(resultat, donneesLen, stocke, 12, 16);
      System.arraycopy(resultat, 0, stocke, 28, donneesLen);

      return Base64.getEncoder().encodeToString(stocke);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Erreur de chiffrement de configuration", e);
    }
  }

  /**
   * Déchiffre une valeur AES-256-GCM.
   *
   * <p>Format attendu : {@code base64(IV(12) || TAG(16) || données_chiffrées)}
   *
   * @param chiffre valeur chiffrée encodée en base64
   * @return valeur en clair (repli sur le secret précédent pendant une rotation)
   */
  public String dechiffrer(final String chiffre) {
    try {
      final byte[] buf = Base64.getDecoder().decode(chiffre);
      final byte[] iv = Arrays.copyOf(buf, 12);
      final byte[] tag = Arrays.copyOfRange(buf, 12, 28);
      final byte[] donnees = Arrays.copyOfRange(buf, 28, buf.length);

      // AES/GCM attend données_chiffrées || TAG
      final byte[] donneesAvecTag = new byte[donnees.length + 16];
      System.arraycopy(donnees, 0, donneesAvecTag, 0, donnees.length);
      System.arraycopy(tag, 0, donneesAvecTag, donnees.length, 16);

      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(cleChiffrement, "AES"),
          new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(donneesAvecTag), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      if (precedent != null) {
        return precedent.dechiffrer(chiffre);
      }
      throw new IllegalStateException("Erreur de déchiffrement de configuration", e);
    }
  }
}
