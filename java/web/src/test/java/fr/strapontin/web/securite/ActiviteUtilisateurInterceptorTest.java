/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests unitaires de {@link ActiviteUtilisateurInterceptor}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ActiviteUtilisateurInterceptorTest {

  private final AtomicLong horloge = new AtomicLong();
  private NamedParameterJdbcTemplate jdbc;
  private ActiviteUtilisateurInterceptor interceptor;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private HttpSession session;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    interceptor = new ActiviteUtilisateurInterceptor(jdbc, horloge::get);
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
  void preHandle_premiereRequeteDeLUtilisateur_marqueLActiviteEtAnnuleLaPurge() {
    authentifier();

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc)
        .update(
            contains("SET derniere_connexion_le = now(), purge_le = NULL"), eq(Map.of("id", "u1")));
  }

  @Test
  void preHandle_activiteDejaMarqueeDansLaJournee_nEcritPas() {
    authentifier();
    interceptor.preHandle(request, response, new Object());
    horloge.addAndGet(Duration.ofHours(2).toNanos());

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc, times(1)).update(anyString(), anyMap());
  }

  @Test
  void preHandle_activiteMarqueeIlYAPlusDUnJour_reecrit() {
    authentifier();
    interceptor.preHandle(request, response, new Object());
    horloge.addAndGet(Duration.ofHours(25).toNanos());

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verify(jdbc, times(2)).update(contains("derniere_connexion_le = now()"), anyMap());
  }

  // Première rafale d'une session neuve : une douzaine de requêtes en parallèle, une seule
  // écriture.
  @Test
  void preHandle_requetesSimultanees_ecritUneSeuleFois() throws Exception {
    authentifier();
    final Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
    final ExecutorService executeur = Executors.newFixedThreadPool(12);
    final CountDownLatch depart = new CountDownLatch(1);
    final List<Future<Boolean>> resultats = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      resultats.add(
          executeur.submit(
              () -> {
                SecurityContextHolder.getContext().setAuthentication(authentification);
                depart.await();
                return interceptor.preHandle(request, response, new Object());
              }));
    }
    depart.countDown();
    for (final Future<Boolean> resultat : resultats) {
      assertTrue(resultat.get());
    }
    executeur.shutdown();

    verify(jdbc, times(1)).update(anyString(), anyMap());
  }

  private static void authentifier() {
    final UtilisateurDetails details =
        new UtilisateurDetails("u1", "login", "test@test.fr", "", "user", false, false, false);
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null, null));
  }
}
