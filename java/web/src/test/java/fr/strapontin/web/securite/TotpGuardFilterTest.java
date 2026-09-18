/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link TotpGuardFilter}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class TotpGuardFilterTest {

  private final TotpGuardFilter filtre = new TotpGuardFilter();

  private HttpServletRequest requete(final String chemin, final HttpSession session) {
    final HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getServletPath()).thenReturn(chemin);
    when(req.getSession(false)).thenReturn(session);
    return req;
  }

  private HttpServletResponse reponse() throws Exception {
    final HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    return resp;
  }

  private HttpSession sessionAvecTotpEnAttente(final boolean enAttente) {
    final HttpSession session = mock(HttpSession.class);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(enAttente);
    return session;
  }

  @Test
  void doFilterInternal_aucuneSession_passeLaRequete() throws Exception {
    final HttpServletRequest req = requete("/api/concerts", null);
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(401);
  }

  @Test
  void doFilterInternal_sessionSansChallengeEnAttente_passeLaRequete() throws Exception {
    final HttpServletRequest req = requete("/api/concerts", sessionAvecTotpEnAttente(false));
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(401);
  }

  @Test
  void doFilterInternal_challengeEnAttenteRouteApiNonAutorisee_retourne401() throws Exception {
    final HttpServletRequest req = requete("/api/concerts", sessionAvecTotpEnAttente(true));
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(resp).setStatus(401);
    verify(chain, never()).doFilter(req, resp);
  }

  @Test
  void doFilterInternal_challengeEnAttenteRouteTotp_passeLaRequete() throws Exception {
    final HttpServletRequest req = requete("/api/auth/totp", sessionAvecTotpEnAttente(true));
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(401);
  }

  @Test
  void doFilterInternal_challengeEnAttenteRouteLogout_passeLaRequete() throws Exception {
    final HttpServletRequest req = requete("/api/auth/logout", sessionAvecTotpEnAttente(true));
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(401);
  }

  @Test
  void doFilterInternal_challengeEnAttenteRouteNonApi_passeLaRequete() throws Exception {
    final HttpServletRequest req = requete("/spectacles", sessionAvecTotpEnAttente(true));
    final HttpServletResponse resp = reponse();
    final FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(401);
  }
}
