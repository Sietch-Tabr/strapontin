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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;
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
  private SetupService setupService;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    setupService = new SetupService(jdbc, mock(ConfigService.class));
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
}
