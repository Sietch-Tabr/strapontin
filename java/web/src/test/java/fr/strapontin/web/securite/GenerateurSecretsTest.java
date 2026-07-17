/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link GenerateurSecrets} (#950).
///
/// @since 2.7.0
/// @author Kwaite
class GenerateurSecretsTest {

  @Test
  void genererHex_nbOctets_retourneChaineDeLongueurDoublee() {
    String hex = GenerateurSecrets.genererHex(16);

    assertEquals(32, hex.length());
    assertTrue(hex.matches("[0-9a-f]+"));
  }

  @Test
  void genererHex_deuxAppels_retourneValeursDistinctes() {
    String premier = GenerateurSecrets.genererHex(16);
    String second = GenerateurSecrets.genererHex(16);

    assertFalse(premier.equals(second));
  }

  @Test
  void genererToken_nbOctets_retourneBase64UrlSansPadding() {
    String token = GenerateurSecrets.genererToken(32);

    assertFalse(token.contains("="), "le token ne doit pas contenir de padding Base64");
    assertFalse(token.contains("+"), "l'alphabet Base64 URL ne doit pas contenir de +");
    assertFalse(token.contains("/"), "l'alphabet Base64 URL ne doit pas contenir de /");
    assertEquals(32, Base64.getUrlDecoder().decode(token).length);
  }

  @Test
  void genererToken_deuxAppels_retourneValeursDistinctes() {
    String premier = GenerateurSecrets.genererToken(32);
    String second = GenerateurSecrets.genererToken(32);

    assertFalse(premier.equals(second));
  }

  @Test
  void sha256Hex_chaineVide_retourneVecteurDeTestConnu() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        GenerateurSecrets.sha256Hex(""));
  }

  @Test
  void sha256Hex_abc_retourneVecteurDeTestConnu() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        GenerateurSecrets.sha256Hex("abc"));
  }

  @Test
  void sha256Hex_memeEntree_retourneToujoursLeMemeHachage() {
    assertEquals(
        GenerateurSecrets.sha256Hex("strapontin"), GenerateurSecrets.sha256Hex("strapontin"));
  }
}
