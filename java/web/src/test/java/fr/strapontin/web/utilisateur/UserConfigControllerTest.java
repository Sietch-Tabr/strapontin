/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires pour {@link UserConfigController} (limite de taille sur le champ départements
 * brut, avant découpage).
 *
 * @since 2.6.5
 * @author Kwaite
 */
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

  // champ trop long : voir RequeteDepartementsValidationTest (@Valid ne s'exerce pas sur un
  // appel Java direct au contrôleur, seulement via Spring MVC).

  @Test
  void majDepartements_champLimiteExacte_appelleLeService() {
    String brut = "7".repeat(1000);
    when(userConfigService.majDepartements(any(), any()))
        .thenReturn(new UserConfigService.ResultatMajDepartements(List.of()));

    controller.majDepartements(new RequeteDepartements(brut), authPour("u1"));

    verify(userConfigService).majDepartements(any(), any());
  }

  @Test
  void majNotifications_delegueAuServiceAvecUserId() {
    RequeteNotifications requete = new RequeteNotifications("quotidien", true, false, false, false);

    controller.majNotifications(requete, authPour("u1"));

    verify(userConfigService).majNotifications("u1", requete);
  }

  @Test
  void majFiltres_delegueAuServiceAvecUserId() {
    controller.majFiltres(new RequeteFiltres(false), authPour("u1"));

    verify(userConfigService).majFiltres("u1", false);
  }

  @Test
  void majTheme_delegueAuServiceEtRetourneLaValeur() {
    var rep = controller.majTheme(new RequeteTheme("sombre"), authPour("u1"));

    verify(userConfigService).majTheme("u1", "sombre");
    assertThat(rep.getBody().get("theme")).isEqualTo("sombre");
  }

  @Test
  void lireConfig_delegueAuServiceAvecUserId() {
    ConfigUtilisateur config =
        new ConfigUtilisateur(List.of(), true, "jamais", false, false, false, false, "auto", false);
    when(userConfigService.lireConfig("u1")).thenReturn(config);

    var rep = controller.lireConfig(authPour("u1"));

    assertThat(rep.getBody()).isSameAs(config);
  }

  @Test
  void onboarding_delegueAuServiceAvecUserId() {
    when(userConfigService.onboardingNonVu("u1")).thenReturn(true);

    var rep = controller.onboarding(authPour("u1"));

    assertThat(rep.getBody().get("nonVu")).isEqualTo(true);
  }

  @Test
  void marquerOnboardingVu_delegueAuServiceAvecUserId() {
    controller.marquerOnboardingVu(authPour("u1"));

    verify(userConfigService).marquerOnboardingVu("u1");
  }
}
