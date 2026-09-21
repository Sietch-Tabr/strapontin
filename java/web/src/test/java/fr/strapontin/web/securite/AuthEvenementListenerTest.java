/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

/**
 * Tests unitaires pour {@link AuthEvenementListener}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class AuthEvenementListenerTest {

  private LogAdminService logAdminService;
  private AlerteActiviteUtilisateurService alerteActiviteUtilisateurService;
  private AuthEvenementListener listener;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    alerteActiviteUtilisateurService = mock(AlerteActiviteUtilisateurService.class);
    listener = new AuthEvenementListener(logAdminService, alerteActiviteUtilisateurService);
  }

  private AbstractAuthenticationFailureEvent evenementEchec(final String identifiant) {
    final UsernamePasswordAuthenticationToken auth =
        UsernamePasswordAuthenticationToken.unauthenticated(identifiant, "x");
    return new AuthenticationFailureBadCredentialsEvent(
        auth, new BadCredentialsException("mauvais mot de passe"));
  }

  @Test
  void surSuccesAuthentification_consigneEnInfo() {
    final UtilisateurDetails details =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "", "user", false, false, false);
    final AuthenticationSuccessEvent event =
        new AuthenticationSuccessEvent(
            UsernamePasswordAuthenticationToken.authenticated(details, null, null));

    listener.surSuccesAuthentification(event);

    verify(logAdminService).logAdmin(eq("info"), eq("auth"), anyString(), anyMap());
  }

  @Test
  void surEchecAuthentification_sousLeSeuil_neDeclenchePasDAlerte() {
    when(logAdminService.compterEchecsConnexionRecents(eq("alice"), anyLong())).thenReturn(3L);

    listener.surEchecAuthentification(evenementEchec("alice"));

    verify(logAdminService).logAdmin(eq("warn"), eq("auth"), anyString(), anyMap());
    verify(alerteActiviteUtilisateurService, never()).notifierEchecsConnexionRepetes(anyString());
  }

  @Test
  void surEchecAuthentification_atteintLeSeuil_declencheLAlerteUneSeuleFois() {
    when(logAdminService.compterEchecsConnexionRecents(eq("alice"), anyLong())).thenReturn(5L);

    listener.surEchecAuthentification(evenementEchec("alice"));

    verify(alerteActiviteUtilisateurService).notifierEchecsConnexionRepetes("alice");
  }

  @Test
  void surEchecAuthentification_depasseLeSeuil_neReDeclenchePasLAlerte() {
    when(logAdminService.compterEchecsConnexionRecents(eq("alice"), anyLong())).thenReturn(6L);

    listener.surEchecAuthentification(evenementEchec("alice"));

    verify(alerteActiviteUtilisateurService, never()).notifierEchecsConnexionRepetes(anyString());
  }
}
