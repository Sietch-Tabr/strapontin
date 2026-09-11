/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests unitaires pour {@link AppareilEnregistrementInterceptor}.
 *
 * @since 2.7.8
 * @author Kwaite
 */
class AppareilEnregistrementInterceptorTest {

  private AppareilsService appareilsService;
  private AppareilEnregistrementInterceptor interceptor;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private HttpSession session;

  @BeforeEach
  void setUp() {
    appareilsService = mock(AppareilsService.class);
    interceptor = new AppareilEnregistrementInterceptor(appareilsService);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    session = mock(HttpSession.class);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void preHandle_sansSession_neFaitRien() {
    when(request.getSession(false)).thenReturn(null);

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(appareilsService, never()).enregistrerAppareil(anyString(), anyString(), anyString());
  }

  @Test
  void preHandle_dejaEnregistreeDansLaSession_neReecritPas() {
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute("appareilEnregistre")).thenReturn(Boolean.TRUE);

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(appareilsService, never()).enregistrerAppareil(anyString(), anyString(), anyString());
  }

  @Test
  void preHandle_pasAuthentifie_neFaitRien() {
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute("appareilEnregistre")).thenReturn(null);

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(appareilsService, never()).enregistrerAppareil(anyString(), anyString(), anyString());
  }

  @Test
  void preHandle_utilisateurAuthentifie_enregistreEtMarqueLaSession() {
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute("appareilEnregistre")).thenReturn(null);
    when(session.getId()).thenReturn("sess-1");
    when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

    UtilisateurDetails details =
        new UtilisateurDetails("u1", "login", "test@test.fr", "", "user", false, false);
    Authentication auth = UsernamePasswordAuthenticationToken.authenticated(details, null, null);
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(appareilsService).enregistrerAppareil("sess-1", "u1", "Mozilla/5.0");
    verify(session).setAttribute("appareilEnregistre", Boolean.TRUE);
  }
}
