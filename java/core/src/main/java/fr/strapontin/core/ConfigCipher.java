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
 * Chiffrement/déchiffrement AES-256-GCM de {@code app_config}, clé dérivée via SCrypt.
 *
 * Extrait de {@code web.ConfigService}/{@code worker.ConfigServiceWorker} — les deux modules dérivaient et
 * utilisaient une clé strictement identique, dupliquée verbatim ; un correctif de sécurité
 * appliqué à un seul module ne se serait jamais répercuté dans l'autre.
 *
 * La clé AES-256 est dérivée de {@code APP_CONFIG_SECRET} via SCrypt (paramètres par défaut Node.js :
 * N=16384, r=8, p=1, dkLen=32) — DIFFÉRENT de {@code BetterAuthPasswordEncoder} (r=16, Better-Auth).
 *
 * Format de stockage : {@code base64(IV(12) || TAG(16) || données_chiffrées)}. Compatible avec les
 * valeurs chiffrées par l'ancien module TypeScript.
 *
 * @since 2.7.0
 * @author Kwaite
 */
public final class ConfigCipher {

  private static final SecureRandom rng = new SecureRandom();

  private static final int N = 16384;
  private static final int R = 8;
  private static final int P = 1;
  private static final int DK_LEN = 32;

  private final byte[] cleChiffrement;

  /**
   * Dérive la clé AES-256 depuis le secret applicatif.
   *
   * @param secret secret applicatif (variable {@code APP_CONFIG_SECRET})
   */
  public ConfigCipher(String secret) {
    Objects.requireNonNull(secret, "secret");
    this.cleChiffrement =
        SCrypt.generate(
            secret.getBytes(StandardCharsets.UTF_8),
            "strapontin-app-config".getBytes(StandardCharsets.UTF_8),
            N,
            R,
            P,
            DK_LEN);
  }

  /**
   * Chiffre une valeur en AES-256-GCM.
   *
   * Format de sortie : {@code base64(IV(12) || TAG(16) || données_chiffrées)}
   *
   * @param valeur valeur en clair
   * @return valeur chiffrée encodée en base64
   */
  public String chiffrer(String valeur) {
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

  /**
   * Déchiffre une valeur AES-256-GCM.
   *
   * Format attendu : {@code base64(IV(12) || TAG(16) || données_chiffrées)}
   *
   * @param chiffre valeur chiffrée encodée en base64
   * @return valeur en clair
   */
  public String dechiffrer(String chiffre) {
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
