/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/// Tests unitaires de {@link BetterAuthPasswordEncoder}.
///
/// <p>Valide la compatibilité avec le format Better-Auth (scrypt Node.js),
/// le re-hashage transparent en bcrypt et la comparaison en temps constant.
///
/// @since 2.0.0
/// @author Kwaite
class BetterAuthPasswordEncoderTest {

  private static final int N = 16384;
  private static final int R = 16;
  private static final int P = 1;
  private static final int DK_LEN = 64;

  private final BetterAuthPasswordEncoder encodeur = new BetterAuthPasswordEncoder();

  // Génère un hash scrypt au format Better-Auth pour les tests
  private static String genererHashBetterAuth(String motDePasse, String saltHex) {
    byte[] sel = saltHex.getBytes(StandardCharsets.UTF_8);
    byte[] mdpBytes = motDePasse.getBytes(StandardCharsets.UTF_8);
    byte[] hash = SCrypt.generate(mdpBytes, sel, N, R, P, DK_LEN);
    return saltHex + ":" + HexFormat.of().formatHex(hash);
  }

  @Test
  void matches_avecFormatBetterAuth_retourneVrai() {
    String saltHex = "0123456789abcdef0123456789abcdef";
    String hash = genererHashBetterAuth("monmotdepasse", saltHex);
    assertTrue(encodeur.matches("monmotdepasse", hash));
  }

  @Test
  void matches_avecFormatBetterAuthEtMauvaisMdp_retourneFaux() {
    String saltHex = "0123456789abcdef0123456789abcdef";
    String hash = genererHashBetterAuth("monmotdepasse", saltHex);
    assertFalse(encodeur.matches("mauvaismdp", hash));
  }

  @Test
  void matches_avecFormatBcrypt_retourneVrai() {
    String hash = encodeur.encode("monmotdepasse");
    assertTrue(encodeur.matches("monmotdepasse", hash));
  }

  @Test
  void matches_avecFormatBcryptEtMauvaisMdp_retourneFaux() {
    String hash = encodeur.encode("monmotdepasse");
    assertFalse(encodeur.matches("mauvaismdp", hash));
  }

  @Test
  void encode_produitToujoursDuBcrypt() {
    String hash = encodeur.encode("quelconque");
    assertTrue(hash.startsWith("$2"), "Le hash doit commencer par $2 (bcrypt)");
  }

  @Test
  void upgradeEncoding_avecFormatBetterAuth_retourneVrai() {
    String saltHex = "0123456789abcdef0123456789abcdef";
    String hash = genererHashBetterAuth("mdp", saltHex);
    assertTrue(encodeur.upgradeEncoding(hash));
  }

  @Test
  void upgradeEncoding_avecFormatBcrypt_retourneFaux() {
    String hash = encodeur.encode("mdp");
    assertFalse(encodeur.upgradeEncoding(hash));
  }

  @Test
  void upgradeEncoding_avecHashNull_retourneVrai() {
    assertTrue(encodeur.upgradeEncoding(null));
  }

  @Test
  void matches_avecHashNull_retourneFaux() {
    assertFalse(encodeur.matches("mdp", null));
  }

  @Test
  void matches_avecHashMalForme_retourneFaux() {
    // Format sans le séparateur ':'
    assertFalse(encodeur.matches("mdp", "pasdehashvalide"));
  }

  @Test
  void matches_selCommeStringHexUtf8_pasCommeBytesDecode() {
    // PIÈGE CRITIQUE : le sel est les bytes UTF-8 du string hex (32 bytes),
    // pas les bytes décodés (16 bytes). Ce test vérifie le comportement correct.
    String saltHex = "deadbeefcafebabe1234567890abcdef";
    String motDePasse = "test";

    // Hash généré avec sel en UTF-8 (correct)
    byte[] selUtf8 = saltHex.getBytes(StandardCharsets.UTF_8);
    byte[] hashBytes = SCrypt.generate(
        motDePasse.getBytes(StandardCharsets.UTF_8), selUtf8, N, R, P, DK_LEN
    );
    String hashCorrect = saltHex + ":" + HexFormat.of().formatHex(hashBytes);

    // Hash généré avec sel décodé (incorrect — ne doit PAS correspondre)
    byte[] selDecode = HexFormat.of().parseHex(saltHex);
    byte[] hashBytesMauvais = SCrypt.generate(
        motDePasse.getBytes(StandardCharsets.UTF_8), selDecode, N, R, P, DK_LEN
    );
    String hashMauvais = saltHex + ":" + HexFormat.of().formatHex(hashBytesMauvais);

    assertTrue(encodeur.matches(motDePasse, hashCorrect), "Doit valider le hash avec sel UTF-8");
    assertFalse(encodeur.matches(motDePasse, hashMauvais), "Ne doit pas valider le hash avec sel décodé");
  }
}
