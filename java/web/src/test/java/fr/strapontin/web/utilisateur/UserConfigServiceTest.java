/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link UserConfigService}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
class UserConfigServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private ConfigService configService;
  private UserConfigService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    configService = mock(ConfigService.class);
    service = new UserConfigService(jdbc, configService, mock(LogAdminService.class));
  }

  @Test
  void majNotifications_frequenceInvalide_leveErreurMetier() {
    final RequeteNotifications requete =
        new RequeteNotifications("invalide", true, false, false, false);

    assertThatThrownBy(() -> service.majNotifications("user-1", requete))
        .isInstanceOf(ErreurMetier.class);
  }

  @Test
  void majNotifications_aucunCanalActifEtFrequenceNonJamais_leveErreurMetier() {
    final RequeteNotifications requete =
        new RequeteNotifications("quotidien", false, false, false, false);

    assertThatThrownBy(() -> service.majNotifications("user-1", requete))
        .isInstanceOf(ErreurMetier.class);
  }

  @Test
  void majNotifications_frequenceJamaisSansCanal_neLevePasErreur() {
    final RequeteNotifications requete =
        new RequeteNotifications("jamais", false, false, false, false);

    service.majNotifications("user-1", requete);

    verify(jdbc).update(anyString(), anyMap());
  }

  @Test
  void majNotifications_frequenceValideAvecCanal_metAJourLaConfig() {
    final RequeteNotifications requete =
        new RequeteNotifications("temps_reel", true, false, false, false);

    service.majNotifications("user-1", requete);

    verify(jdbc).update(anyString(), anyMap());
  }

  @Test
  void majTheme_valeurInvalide_leveErreurMetier() {
    assertThatThrownBy(() -> service.majTheme("user-1", "psychedelique"))
        .isInstanceOf(ErreurMetier.class);
  }

  @Test
  void majTheme_valeurValide_metAJourLaConfig() {
    service.majTheme("user-1", "sombre");

    verify(jdbc).update(anyString(), anyMap());
  }

  @Test
  void majDepartements_codesCorseA_normaliseVers20() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final UserConfigService.ResultatMajDepartements resultat =
        service.majDepartements("user-1", List.of("2A"));

    assertThat(resultat.ignores()).isEmpty();
  }

  @Test
  void majDepartements_codeDomTom3Chiffres_accepte() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final UserConfigService.ResultatMajDepartements resultat =
        service.majDepartements("user-1", List.of("974"));

    assertThat(resultat.ignores()).isEmpty();
  }

  @Test
  void majDepartements_codeFormatInvalide_estIgnore() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final UserConfigService.ResultatMajDepartements resultat =
        service.majDepartements("user-1", List.of("abc"));

    assertThat(resultat.ignores()).containsExactly("abc");
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireConfig_aucuneLigne_retourneValeursParDefaut() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    final ConfigUtilisateur config = service.lireConfig("user-1");

    assertThat(config.departements()).isEmpty();
    assertThat(config.filtreDisponibles()).isTrue();
    assertThat(config.frequenceNotifications()).isEqualTo("jamais");
    assertThat(config.theme()).isEqualTo("auto");
    assertThat(config.resendDisponible()).isFalse();
  }

  @Test
  void lireFiltreDisponibles_aucuneLigne_retourneVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
        .thenReturn(List.of());

    assertThat(service.lireFiltreDisponibles("user-1")).isTrue();
  }

  @Test
  void lireFiltreDisponibles_ligneExistanteFausse_retourneFaux() {
    when(jdbc.queryForList(anyString(), anyMap(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
        .thenReturn(List.of(false));

    assertThat(service.lireFiltreDisponibles("user-1")).isFalse();
  }

  @Test
  void onboardingNonVu_aucuneLigne_retourneVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
        .thenReturn(List.of());

    assertThat(service.onboardingNonVu("user-1")).isTrue();
  }

  @Test
  void onboardingNonVu_ligneExistanteVue_retourneFaux() {
    when(jdbc.queryForList(anyString(), anyMap(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
        .thenReturn(List.of(true));

    assertThat(service.onboardingNonVu("user-1")).isFalse();
  }

  @Test
  void marquerOnboardingVu_metAJourLaConfig() {
    service.marquerOnboardingVu("user-1");

    verify(jdbc).update(anyString(), anyMap());
  }
}
