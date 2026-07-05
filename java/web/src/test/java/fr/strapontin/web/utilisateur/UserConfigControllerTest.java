/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/// Tests unitaires pour {@link UserConfigController} (#849 — limite de taille sur le champ
/// départements brut, avant découpage).
///
/// @since 2.6.5
/// @author Kwaite
class UserConfigControllerTest {

  private UserConfigService userConfigService;
  private UserConfigController controller;

  @BeforeEach
  void setUp() {
    userConfigService = mock(UserConfigService.class);
    controller = new UserConfigController(userConfigService);
  }

  private static Authentication authPour(String userId) {
    UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void majDepartements_champTropLong_leveErreurMetierSansAppelerLeService() {
    String brut = "7".repeat(1001);

    assertThatThrownBy(
            () -> controller.majDepartements(new RequeteDepartements(brut), authPour("u1")))
        .isInstanceOf(ErreurMetier.class);

    verify(userConfigService, never()).majDepartements(any(), any());
  }

  @Test
  void majDepartements_champLimiteExacte_appelleLeService() {
    String brut = "7".repeat(1000);
    when(userConfigService.majDepartements(any(), any()))
        .thenReturn(new UserConfigService.ResultatMajDepartements(List.of()));

    controller.majDepartements(new RequeteDepartements(brut), authPour("u1"));

    verify(userConfigService).majDepartements(any(), any());
  }
}
