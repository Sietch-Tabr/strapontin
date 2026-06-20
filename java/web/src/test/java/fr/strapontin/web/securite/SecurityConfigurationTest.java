/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/// Tests unitaires pour les beans de sécurité de {@link SecurityConfiguration}.
///
/// <p>Couvre les propriétés des cookies de session et l'encodeur de mots de passe.
/// Les règles d'autorisation ({@code securityFilterChain}) sont vérifiées par les
/// tests E2E Playwright (auth.spec.js).
///
/// @since 2.2.5
/// @author Kwaite
class SecurityConfigurationTest {

  private final SecurityConfiguration config = new SecurityConfiguration();

  // --- cookieSerializer ---

  @Test
  void cookieSerializer_avecSecureFalse_retourneDefaultCookieSerializer() {
    CookieSerializer serialiseur = config.cookieSerializer(false);
    assertInstanceOf(DefaultCookieSerializer.class, serialiseur);
  }

  @Test
  void cookieSerializer_maxAge_est30Jours() {
    DefaultCookieSerializer serialiseur = (DefaultCookieSerializer) config.cookieSerializer(false);
    assertEquals(2592000, lireMaxAge(serialiseur));
  }

  @Test
  void cookieSerializer_httpOnly_estActif() {
    DefaultCookieSerializer serialiseur = (DefaultCookieSerializer) config.cookieSerializer(false);
    assertTrue(lireHttpOnly(serialiseur));
  }

  @Test
  void cookieSerializer_sameSite_estLax() {
    DefaultCookieSerializer serialiseur = (DefaultCookieSerializer) config.cookieSerializer(false);
    assertEquals("Lax", lireSameSite(serialiseur));
  }

  // --- passwordEncoder ---

  @Test
  void passwordEncoder_estBCrypt() {
    PasswordEncoder encodeur = config.passwordEncoder();
    assertInstanceOf(BCryptPasswordEncoder.class, encodeur);
  }

  @Test
  void passwordEncoder_encodeEtMatches_fonctionnent() {
    PasswordEncoder encodeur = config.passwordEncoder();
    String hash = encodeur.encode("motdepasse12345");
    assertTrue(encodeur.matches("motdepasse12345", hash));
    assertFalse(encodeur.matches("autremotdepasse", hash));
  }

  // --- Helpers réflexifs pour lire les champs privés de DefaultCookieSerializer ---

  private static int lireMaxAge(DefaultCookieSerializer s) {
    try {
      var champ = DefaultCookieSerializer.class.getDeclaredField("cookieMaxAge");
      champ.setAccessible(true);
      return (int) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire cookieMaxAge", e);
    }
  }

  private static boolean lireHttpOnly(DefaultCookieSerializer s) {
    try {
      var champ = DefaultCookieSerializer.class.getDeclaredField("useHttpOnlyCookie");
      champ.setAccessible(true);
      return (boolean) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire useHttpOnlyCookie", e);
    }
  }

  private static String lireSameSite(DefaultCookieSerializer s) {
    try {
      var champ = DefaultCookieSerializer.class.getDeclaredField("sameSite");
      champ.setAccessible(true);
      return (String) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire sameSite", e);
    }
  }
}
