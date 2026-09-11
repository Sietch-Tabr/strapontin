/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Tests unitaires pour les beans de sécurité de {@link SecurityConfiguration}.
 *
 * <p>Couvre les propriétés des cookies de session et l'encodeur de mots de passe. Les règles
 * d'autorisation ({@code securityFilterChain}) sont vérifiées par les tests E2E Playwright
 * (auth.spec.js).
 *
 * @since 2.2.5
 * @author Kwaite
 */
class SecurityConfigurationTest {

  private final SecurityConfiguration config = new SecurityConfiguration();

  // --- authenticationManager (publication des événements d'authentification) ---

  @Test
  void authenticationManager_credentialsValides_publieAuthenticationSuccessEvent() {
    final PasswordEncoder encodeur = new BCryptPasswordEncoder();
    final UserDetails utilisateur =
        User.withUsername("alice")
            .password(encodeur.encode("motdepasse12345"))
            .roles("USER")
            .build();
    final UserDetailsService userDetailsService = _ -> utilisateur;
    final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    final AuthenticationManager authenticationManager =
        config.authenticationManager(userDetailsService, encodeur, eventPublisher);
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken("alice", "motdepasse12345"));

    verify(eventPublisher).publishEvent(any(AuthenticationSuccessEvent.class));
  }

  @Test
  void authenticationManager_credentialsInvalides_publieAuthenticationFailureEvent() {
    final PasswordEncoder encodeur = new BCryptPasswordEncoder();
    final UserDetails utilisateur =
        User.withUsername("alice")
            .password(encodeur.encode("motdepasse12345"))
            .roles("USER")
            .build();
    final UserDetailsService userDetailsService = _ -> utilisateur;
    final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    final AuthenticationManager authenticationManager =
        config.authenticationManager(userDetailsService, encodeur, eventPublisher);

    assertThrows(
        BadCredentialsException.class,
        () ->
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("alice", "mauvais-mot-de-passe")));
    verify(eventPublisher).publishEvent(any(AbstractAuthenticationFailureEvent.class));
  }

  // --- cookieSerializer ---

  @Test
  void cookieSerializer_avecSecureFalse_retourneDefaultCookieSerializer() {
    final CookieSerializer serialiseur = config.cookieSerializer(false);
    assertInstanceOf(DefaultCookieSerializer.class, serialiseur);
  }

  @Test
  void cookieSerializer_maxAge_est30Jours() {
    final DefaultCookieSerializer serialiseur =
        (DefaultCookieSerializer) config.cookieSerializer(false);
    assertEquals(2592000, lireMaxAge(serialiseur));
  }

  @Test
  void cookieSerializer_httpOnly_estActif() {
    final DefaultCookieSerializer serialiseur =
        (DefaultCookieSerializer) config.cookieSerializer(false);
    assertTrue(lireHttpOnly(serialiseur));
  }

  @Test
  void cookieSerializer_sameSite_estLax() {
    final DefaultCookieSerializer serialiseur =
        (DefaultCookieSerializer) config.cookieSerializer(false);
    assertEquals("Lax", lireSameSite(serialiseur));
  }

  // --- passwordEncoder ---

  @Test
  void passwordEncoder_estBCrypt() {
    final PasswordEncoder encodeur = config.passwordEncoder();
    assertInstanceOf(BCryptPasswordEncoder.class, encodeur);
  }

  @Test
  void passwordEncoder_encodeEtMatches_fonctionnent() {
    final PasswordEncoder encodeur = config.passwordEncoder();
    final String hash = encodeur.encode("motdepasse12345");
    assertTrue(encodeur.matches("motdepasse12345", hash));
    assertFalse(encodeur.matches("autremotdepasse", hash));
  }

  // --- Helpers réflexifs pour lire les champs privés de DefaultCookieSerializer ---

  private static int lireMaxAge(final DefaultCookieSerializer s) {
    try {
      final var champ = DefaultCookieSerializer.class.getDeclaredField("cookieMaxAge");
      champ.setAccessible(true);
      return (int) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire cookieMaxAge", e);
    }
  }

  private static boolean lireHttpOnly(final DefaultCookieSerializer s) {
    try {
      final var champ = DefaultCookieSerializer.class.getDeclaredField("useHttpOnlyCookie");
      champ.setAccessible(true);
      return (boolean) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire useHttpOnlyCookie", e);
    }
  }

  private static String lireSameSite(final DefaultCookieSerializer s) {
    try {
      final var champ = DefaultCookieSerializer.class.getDeclaredField("sameSite");
      champ.setAccessible(true);
      return (String) champ.get(s);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Impossible de lire sameSite", e);
    }
  }
}
