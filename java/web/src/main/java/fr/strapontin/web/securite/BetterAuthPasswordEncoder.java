/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/// Encodeur de mots de passe compatible avec Better-Auth (scrypt Node.js).
///
/// Deux formats sont supportés :
/// - Format Better-Auth `saltHex:hashHex` — vérifié via BouncyCastle SCrypt
/// - Format bcrypt `$2...` — délégué à [BCryptPasswordEncoder]
///
/// [encode] produit toujours du bcrypt. [upgradeEncoding] retourne
/// `true` pour le format Better-Auth, ce qui déclenche un re-hashage transparent
/// en bcrypt au prochain login — aucune action requise de la part des utilisateurs.
///
/// Paramètres scrypt Better-Auth (oslo/oslojs) : N=16 384, r=16, p=1, dkLen=64.
///
/// @since 2.0.0
/// @author Kwaite
public class BetterAuthPasswordEncoder implements PasswordEncoder {

  private static final int N = 16384;
  private static final int R = 16;
  private static final int P = 1;
  private static final int DK_LEN = 64;

  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private final HexFormat hexFormat = HexFormat.of();

  /// Encode un mot de passe en bcrypt (utilisé pour les nouveaux comptes et re-hashages).
  ///
  /// @param motDePasse le mot de passe en clair
  /// @return le hash bcrypt
  @Override
  public String encode(CharSequence motDePasse) {
    return bcrypt.encode(motDePasse);
  }

  /// Vérifie un mot de passe contre le hash stocké en base de données.
  ///
  /// <p>Détecte automatiquement le format du hash (bcrypt ou Better-Auth scrypt).
  ///
  /// @param motDePasse  le mot de passe en clair
  /// @param hashStocke le hash stocké en base de données
  /// @return {@code true} si le mot de passe correspond
  @Override
  public boolean matches(CharSequence motDePasse, String hashStocke) {
    if (hashStocke != null && hashStocke.startsWith("$2")) {
      return bcrypt.matches(motDePasse, hashStocke);
    }
    return verifierScrypt(motDePasse.toString(), hashStocke);
  }

  /// Indique si le hash nécessite un ré-encodage.
  ///
  /// Retourne `true` pour le format Better-Auth (`saltHex:hashHex`),
  /// ce qui déclenche un re-hashage transparent en bcrypt lors du prochain login.
  ///
  /// @param hashStocke le hash stocké en base de données
  /// @return `true` si le format n'est pas bcrypt
  @Override
  public boolean upgradeEncoding(String hashStocke) {
    return hashStocke == null || !hashStocke.startsWith("$2");
  }

  /// Vérifie un mot de passe avec SCrypt (BouncyCastle) selon les paramètres Better-Auth.
  ///
  /// PIÈGE CRITIQUE : Better-Auth passe le sel à `node:crypto.scrypt()` comme
  /// string hexadécimal UTF-8 (32 bytes), jamais comme bytes décodés (16 bytes).
  /// `saltHex.getBytes(UTF_8)` est correct — `HexFormat.parseHex(saltHex)` ne l'est pas.
  ///
  /// @param motDePasse le mot de passe en clair
  /// @param hashStocke le hash au format {@code saltHex:hashHex}
  /// @return {@code true} si le mot de passe correspond
  private boolean verifierScrypt(String motDePasse, String hashStocke) {
    if (hashStocke == null) {
      return false;
    }
    String[] parties = hashStocke.split(":", 2);
    if (parties.length != 2) {
      return false;
    }
    String saltHex = parties[0];
    String hashHex = parties[1];

    // Sel passé comme bytes UTF-8 du string hex (comportement de node:crypto.scrypt)
    byte[] sel = saltHex.getBytes(StandardCharsets.UTF_8);
    byte[] motDePasseBytes = motDePasse.getBytes(StandardCharsets.UTF_8);
    byte[] hashCalcule = SCrypt.generate(motDePasseBytes, sel, N, R, P, DK_LEN);
    byte[] hashAttendu = hexFormat.parseHex(hashHex);

    return MessageDigest.isEqual(hashCalcule, hashAttendu);
  }
}
