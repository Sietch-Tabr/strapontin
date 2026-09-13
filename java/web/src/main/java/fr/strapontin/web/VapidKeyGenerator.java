/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;

/**
 * Génération d'une paire de clés VAPID EC P-256 (secp256r1) pour les notifications push PWA,
 * encodées en Base64 URL-safe (point non compressé pour la clé publique) — partagée entre {@link
 * fr.strapontin.web.admin.VapidService} (régénération admin) et {@link
 * fr.strapontin.web.setup.SetupService} (première génération au setup initial).
 *
 * @since 2.8.7
 * @author Kwaite
 */
public final class VapidKeyGenerator {

  // Taille en octets d'une coordonnée EC P-256 (256 bits).
  private static final int TAILLE_COORDONNEE_P256 = 32;

  private VapidKeyGenerator() {}

  /**
   * Paire de clés VAPID encodées en Base64 URL-safe.
   *
   * @param clePublique clé publique EC P-256, point non compressé
   * @param clePrivee clé privée EC P-256
   */
  public record PaireVapid(String clePublique, String clePrivee) {}

  /**
   * Génère une nouvelle paire de clés VAPID EC P-256.
   *
   * @return la paire de clés encodées
   * @throws GeneralSecurityException si la génération de clés échoue
   */
  public static PaireVapid genererPaire() throws GeneralSecurityException {
    final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    final KeyPair paire = kpg.generateKeyPair();

    final ECPublicKey pubKey = (ECPublicKey) paire.getPublic();
    final ECPoint w = pubKey.getW();
    final byte[] uncompressed = new byte[65];
    uncompressed[0] = 0x04;
    final byte[] x = vers32Octets(w.getAffineX());
    final byte[] y = vers32Octets(w.getAffineY());
    System.arraycopy(x, 0, uncompressed, 1, TAILLE_COORDONNEE_P256);
    System.arraycopy(y, 0, uncompressed, 1 + TAILLE_COORDONNEE_P256, TAILLE_COORDONNEE_P256);
    final String clePublique = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressed);

    final ECPrivateKey privKey = (ECPrivateKey) paire.getPrivate();
    final byte[] d = vers32Octets(privKey.getS());
    final String clePrivee = Base64.getUrlEncoder().withoutPadding().encodeToString(d);

    return new PaireVapid(clePublique, clePrivee);
  }

  private static byte[] vers32Octets(final BigInteger n) {
    final byte[] raw = n.toByteArray();
    if (raw.length == TAILLE_COORDONNEE_P256) {
      return raw;
    }
    final byte[] result = new byte[TAILLE_COORDONNEE_P256];
    if (raw.length > TAILLE_COORDONNEE_P256) {
      System.arraycopy(raw, raw.length - TAILLE_COORDONNEE_P256, result, 0, TAILLE_COORDONNEE_P256);
    } else {
      System.arraycopy(raw, 0, result, TAILLE_COORDONNEE_P256 - raw.length, raw.length);
    }
    return result;
  }
}
