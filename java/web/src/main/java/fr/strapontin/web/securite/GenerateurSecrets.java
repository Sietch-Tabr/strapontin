/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Génération de secrets et de hachages pour l'invitation et la réinitialisation de comptes.
 *
 * <p>Primitives techniques : identifiants temporaires, tokens d'invitation/reset et
 * empreinte SHA-256 des tokens persistés. Le {@link SecureRandom} est partagé (thread-safe).
 *
 * @since 2.2.3
 * @author Kwaite
 */
public final class GenerateurSecrets {

  private static final SecureRandom RNG = new SecureRandom();

  private GenerateurSecrets() {
    // Classe utilitaire — pas d'instanciation
  }

  /**
   * Génère une chaîne hexadécimale aléatoire.
   *
   * @param nbOctets nombre d'octets aléatoires (la chaîne fait {@code 2 * nbOctets} caractères)
   * @return la chaîne hexadécimale
   */
  public static String genererHex(final int nbOctets) {
    final byte[] octets = new byte[nbOctets];
    RNG.nextBytes(octets);
    final StringBuilder sb = new StringBuilder();
    for (final byte b : octets) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Génère un token aléatoire encodé en Base64 URL (sans padding).
   *
   * @param nbOctets nombre d'octets aléatoires
   * @return le token encodé
   */
  public static String genererToken(final int nbOctets) {
    final byte[] octets = new byte[nbOctets];
    RNG.nextBytes(octets);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
  }

  /**
   * Calcule l'empreinte SHA-256 d'une chaîne, en hexadécimal.
   *
   * @param entree la chaîne à hacher
   * @return l'empreinte hexadécimale
   * @throws IllegalStateException si l'algorithme SHA-256 est indisponible
   */
  public static String sha256Hex(final String entree) {
    try {
      final MessageDigest md = MessageDigest.getInstance("SHA-256");
      final byte[] hash = md.digest(entree.getBytes(StandardCharsets.UTF_8));
      final StringBuilder sb = new StringBuilder();
      for (final byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 non disponible", e);
    }
  }
}
