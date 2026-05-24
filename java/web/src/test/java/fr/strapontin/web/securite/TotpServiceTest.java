/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

  private TotpService service;

  @BeforeEach
  void init() {
    service = new TotpService();
  }

  @Test
  void genererSecret_formatBase32_longueurEtAlphabet() {
    String secret = service.genererSecret();
    assertNotNull(secret);
    assertEquals(32, secret.length(), "Le secret doit faire 32 caractères Base32");
    assertTrue(secret.matches("[A-Z2-7]+"), "Le secret doit être en Base32 (A-Z, 2-7)");
  }

  @Test
  void genererSecret_deuxAppels_secretsDifferents() {
    String s1 = service.genererSecret();
    String s2 = service.genererSecret();
    assertNotEquals(s1, s2, "Deux secrets générés doivent être différents");
  }

  @Test
  void genererUriOtpAuth_formatUri_contientComposants() {
    String secret = service.genererSecret();
    String uri = service.genererUriOtpAuth(secret, "kwaite");
    assertTrue(uri.startsWith("otpauth://totp/"), "L'URI doit commencer par otpauth://totp/");
    assertTrue(uri.contains("secret=" + secret), "L'URI doit contenir le secret");
    assertTrue(uri.contains("issuer=Strapontin"), "L'URI doit contenir l'issuer");
    assertTrue(uri.contains("algorithm=SHA1"), "L'URI doit préciser l'algorithme SHA1");
    assertTrue(uri.contains("digits=6"), "L'URI doit préciser 6 chiffres");
    assertTrue(uri.contains("period=30"), "L'URI doit préciser la période de 30s");
  }

  @Test
  void genererQrCodeSvg_uriValide_retourneSvg() {
    String secret = service.genererSecret();
    String uri = service.genererUriOtpAuth(secret, "kwaite");
    String svg = service.genererQrCodeSvg(uri);
    assertNotNull(svg, "Le QR code SVG ne doit pas être null");
    assertTrue(svg.contains("<svg"), "Le résultat doit être du SVG");
  }

  @Test
  void base32RoundTrip_octetsAleatoires_identiques() {
    byte[] original = new byte[20];
    for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 13 + 7);
    String encode = TotpService.base32Encoder(original);
    byte[] decode = TotpService.base32Decoder(encode);
    assertArrayEquals(original, decode, "Le round-trip Base32 doit être sans perte");
  }

  @Test
  void verifierCode_secretNull_retourneFaux() {
    assertFalse(service.verifierCode(null, "123456"));
  }

  @Test
  void verifierCode_codeNull_retourneFaux() {
    String secret = service.genererSecret();
    assertFalse(service.verifierCode(secret, null));
  }

  @Test
  void verifierCode_codeTropCourt_retourneFaux() {
    String secret = service.genererSecret();
    assertFalse(service.verifierCode(secret, "12345"));
  }

  @Test
  void verifierCode_codeLettres_retourneFaux() {
    String secret = service.genererSecret();
    assertFalse(service.verifierCode(secret, "abcdef"));
  }

  @Test
  void verifierCode_mauvaisCode_retourneFaux() {
    String secret = service.genererSecret();
    assertFalse(service.verifierCode(secret, "000000"));
  }

  @Test
  void verifierCode_codeCorrect_retourneVrai() throws Exception {
    String secret = service.genererSecret();
    byte[] secretOctets = TotpService.base32Decoder(secret);
    long compteur = System.currentTimeMillis() / 1000L / 30L;
    Method calculer = TotpService.class.getDeclaredMethod("calculerCode", byte[].class, long.class);
    calculer.setAccessible(true);
    String code = (String) calculer.invoke(service, secretOctets, compteur);
    assertTrue(service.verifierCode(secret, code), "Le code calculé doit être accepté");
  }
}
