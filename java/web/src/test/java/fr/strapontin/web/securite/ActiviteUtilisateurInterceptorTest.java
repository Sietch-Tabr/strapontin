/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests unitaires de {@link ActiviteUtilisateurInterceptor}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ActiviteUtilisateurInterceptorTest {

  private static final String ATTRIBUT = "activiteMarqueeLe";

  private NamedParameterJdbcTemplate jdbc;
  private ActiviteUtilisateurInterceptor interceptor;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private HttpSession session;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    interceptor = new ActiviteUtilisateurInterceptor(jdbc);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void preHandle_pasAuthentifie_nEcritRien() {
    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void preHandle_premiereRequeteDeLaSession_marqueLActiviteEtAnnuleLaPurge() {
    authentifier();

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc)
        .update(
            contains("SET derniere_connexion_le = now(), purge_le = NULL"), eq(Map.of("id", "u1")));
    verify(session).setAttribute(eq(ATTRIBUT), any(Instant.class));
  }

  @Test
  void preHandle_activiteDejaMarqueeDansLaJournee_nEcritPas() {
    authentifier();
    when(session.getAttribute(ATTRIBUT)).thenReturn(Instant.now().minus(Duration.ofHours(2)));

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void preHandle_activiteMarqueeIlYAPlusDUnJour_reecrit() {
    authentifier();
    when(session.getAttribute(ATTRIBUT)).thenReturn(Instant.now().minus(Duration.ofHours(25)));

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc).update(contains("derniere_connexion_le = now()"), anyMap());
  }

  private static void authentifier() {
    final UtilisateurDetails details =
        new UtilisateurDetails("u1", "login", "test@test.fr", "", "user", false, false, false);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null, null));
  }
}
