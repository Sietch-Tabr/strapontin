/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/// Tests unitaires pour {@link RateLimitingLoginFilter}.
///
/// @since 2.5.0
/// @author Kwaite
class RateLimitingLoginFilterTest {

  private RateLimitingLoginFilter filtre;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    filtre = new RateLimitingLoginFilter(logAdminService);
  }

  private HttpServletRequest requete(String methode, String chemin, String ip) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(methode);
    when(req.getServletPath()).thenReturn(chemin);
    when(req.getRemoteAddr()).thenReturn(ip);
    return req;
  }

  private HttpServletResponse reponse() throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    return resp;
  }

  @Test
  void doFilterInternal_requeteGet_passeSansLimite() throws Exception {
    HttpServletRequest req = requete("GET", "/api/auth/login", "1.2.3.4");
    HttpServletResponse resp = reponse();
    FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void doFilterInternal_urlNonLimitee_passeSansLimite() throws Exception {
    HttpServletRequest req = requete(HttpMethod.POST.name(), "/api/concerts", "1.2.3.4");
    HttpServletResponse resp = reponse();
    FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void doFilterInternal_loginDansLimite_passeLaRequete() throws Exception {
    HttpServletRequest req = requete(HttpMethod.POST.name(), "/api/auth/login", "5.6.7.8");
    HttpServletResponse resp = reponse();
    FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(429);
  }

  @Test
  void doFilterInternal_resetPasswordDansLimite_passeLaRequete() throws Exception {
    HttpServletRequest req =
        requete(HttpMethod.POST.name(), "/api/auth/reset-password", "9.10.11.12");
    HttpServletResponse resp = reponse();
    FilterChain chain = mock(FilterChain.class);

    filtre.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(429);
  }

  @Test
  void doFilterInternal_loginDepasse15Tentatives_retourne429() throws Exception {
    FilterChain chain = mock(FilterChain.class);
    String ip = "10.0.0.1";

    for (int i = 0; i < 15; i++) {
      HttpServletRequest req = requete(HttpMethod.POST.name(), "/api/auth/login", ip);
      HttpServletResponse resp = reponse();
      filtre.doFilter(req, resp, chain);
    }

    HttpServletRequest req16 = requete(HttpMethod.POST.name(), "/api/auth/login", ip);
    HttpServletResponse resp16 = reponse();
    filtre.doFilter(req16, resp16, chain);

    verify(resp16).setStatus(429);
    verify(chain, times(15)).doFilter(any(), any());
  }

  @Test
  void doFilterInternal_resetPasswordBucketIndependantDuLogin_n_epuisePasLeBucketLogin()
      throws Exception {
    FilterChain chain = mock(FilterChain.class);
    String ip = "10.0.0.2";

    for (int i = 0; i < 15; i++) {
      HttpServletRequest req = requete(HttpMethod.POST.name(), "/api/auth/reset-password", ip);
      HttpServletResponse resp = reponse();
      filtre.doFilter(req, resp, chain);
    }

    HttpServletRequest reqLogin = requete(HttpMethod.POST.name(), "/api/auth/login", ip);
    HttpServletResponse respLogin = reponse();
    filtre.doFilter(reqLogin, respLogin, chain);

    verify(respLogin, never()).setStatus(429);
  }
}
