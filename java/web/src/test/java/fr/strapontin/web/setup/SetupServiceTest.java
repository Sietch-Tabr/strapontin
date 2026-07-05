/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de {@link SetupService} — isolation SERIALIZABLE et retry (#803).
///
/// <p>Sans isolation SERIALIZABLE, deux appels concurrents à {@code POST /api/setup} pouvaient
/// tous deux passer la garde {@link SetupService#estDejaConfiguree} avant qu'aucun ne committe,
/// créant deux comptes admin. Le retry gère le conflit de sérialisation levé par PostgreSQL dans
/// ce cas, pour que le perdant de la course échoue proprement sur {@link RessourceDejaExistante}
/// plutôt que de remonter une exception technique.
///
/// @since 2.6.3
/// @author Kwaite
class SetupServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private ConfigService configService;
  private SetupService setupService;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    configService = mock(ConfigService.class);
    setupService = new SetupService(jdbc, configService);
    setupService.setSelf(setupService);
  }

  private static RequeteSetup requeteValide() {
    return new RequeteSetup(
        "admin", "admin@exemple.fr", "motdepasse12345", "https://strapontin.fr", null);
  }

  @Test
  void initialiser_dejaConfiguree_leveRessourceDejaExistante() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);

    assertThrows(RessourceDejaExistante.class, () -> setupService.initialiser(requeteValide()));
  }

  @Test
  void initialiser_conflitSerialisationPuisSucces_reessaieEtReussit() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new CannotSerializeTransactionException("conflit"))
        .thenReturn(1)
        .thenReturn(1);

    setupService.initialiser(requeteValide());

    verify(jdbc, atLeast(2)).update(anyString(), anyMap());
  }

  @Test
  void initialiser_conflitSerialisationPersistant_leveApresMaxTentatives() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new CannotSerializeTransactionException("conflit"));

    assertThrows(
        CannotSerializeTransactionException.class, () -> setupService.initialiser(requeteValide()));
    verify(jdbc, times(3)).update(anyString(), anyMap());
  }

  @Test
  void initialiserTransactionnel_identifiantTropCourt_leveErreurMetier() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete =
        new RequeteSetup("ab", "admin@exemple.fr", "motdepasse12345", null, null);

    assertThrows(ErreurMetier.class, () -> setupService.initialiserTransactionnel(requete));
  }

  @Test
  void initialiserTransactionnel_identifiantFormatInvalide_leveErreurMetier() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete =
        new RequeteSetup("admin!", "admin@exemple.fr", "motdepasse12345", null, null);

    assertThrows(ErreurMetier.class, () -> setupService.initialiserTransactionnel(requete));
  }

  @Test
  void initialiserTransactionnel_emailInvalide_leveErreurMetier() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete = new RequeteSetup("admin", "pas-un-email", "motdepasse12345", null, null);

    assertThrows(ErreurMetier.class, () -> setupService.initialiserTransactionnel(requete));
  }

  @Test
  void initialiserTransactionnel_motDePasseTropCourt_leveErreurMetier() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete = new RequeteSetup("admin", "admin@exemple.fr", "court", null, null);

    assertThrows(ErreurMetier.class, () -> setupService.initialiserTransactionnel(requete));
  }

  @Test
  void initialiserTransactionnel_conflitInsertionConcurrente_leveRessourceDejaExistante() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new DataIntegrityViolationException("contrainte violée"));

    assertThrows(
        RessourceDejaExistante.class,
        () -> setupService.initialiserTransactionnel(requeteValide()));
  }

  @Test
  void initialiserTransactionnel_requeteValide_genereLesClesVapid() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    setupService.initialiserTransactionnel(requeteValide());

    verify(configService).sauvegarderConfig(eq("VAPID_PUBLIC_KEY"), anyString());
    verify(configService).sauvegarderConfig(eq("VAPID_PRIVATE_KEY"), anyString());
  }

  @Test
  void initialiserTransactionnel_contactEmailAbsent_repliSurEmailAdmin() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete =
        new RequeteSetup(
            "admin", "admin@exemple.fr", "motdepasse12345", "https://strapontin.fr", "");

    setupService.initialiserTransactionnel(requete);

    verify(configService).sauvegarderConfig("CONTACT_EMAIL", "admin@exemple.fr");
  }

  @Test
  void initialiserTransactionnel_baseUrlAbsente_neSauvegardeRienPourPublicBaseUrl() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    RequeteSetup requete =
        new RequeteSetup("admin", "admin@exemple.fr", "motdepasse12345", null, null);

    setupService.initialiserTransactionnel(requete);

    verify(configService, never()).sauvegarderConfig(eq("PUBLIC_BASE_URL"), anyString());
  }

  @Test
  void initialiserTransactionnel_baseUrlPresente_sauvegardePublicBaseUrl() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    setupService.initialiserTransactionnel(requeteValide());

    verify(configService).sauvegarderConfig("PUBLIC_BASE_URL", "https://strapontin.fr");
  }
}
