/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jeton authentifiant les appels du module web vers l'API interne du worker ({@code /trigger}).
 *
 * <p>L'écoute sur {@code 127.0.0.1} ne suffit pas : une requête émise par le serveur web lui-même
 * (ex. URL fournie par un utilisateur) y parvient aussi. Le jeton est dérivé par HMAC-SHA256 de
 * {@code APP_CONFIG_SECRET}, déjà connu des deux processus : aucun secret supplémentaire à
 * provisionner, et une rotation de ce secret renouvelle le jeton des deux côtés à la fois.
 *
 * @since 3.0.2
 * @author Kwaite
 */
public final class JetonInterneWorker {

  /** En-tête HTTP portant le jeton. */
  public static final String ENTETE = "X-Jeton-Worker";

  private static final String USAGE = "strapontin:api-interne-worker";
  private static final String ALGORITHME = "HmacSHA256";

  private JetonInterneWorker() {}

  /**
   * Calcule le jeton attendu.
   *
   * @param secret valeur de {@code APP_CONFIG_SECRET}
   * @return le jeton, en hexadécimal
   * @throws IllegalArgumentException si le secret est vide
   */
  public static String calculer(final String secret) {
    Objects.requireNonNull(secret, "secret");
    if (secret.isBlank()) {
      throw new IllegalArgumentException(
          "APP_CONFIG_SECRET vide : jeton de l'API interne impossible");
    }
    try {
      final Mac mac = Mac.getInstance(ALGORITHME);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHME));
      return HexFormat.of().formatHex(mac.doFinal(USAGE.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 indisponible", e);
    }
  }

  /**
   * Vérifie un jeton reçu, en temps constant.
   *
   * @param secret valeur de {@code APP_CONFIG_SECRET}
   * @param jetonRecu jeton reçu, peut être null
   * @return true si le jeton est valide
   */
  public static boolean estValide(final String secret, final String jetonRecu) {
    if (jetonRecu == null || secret == null || secret.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        calculer(secret).getBytes(StandardCharsets.UTF_8),
        jetonRecu.getBytes(StandardCharsets.UTF_8));
  }
}
