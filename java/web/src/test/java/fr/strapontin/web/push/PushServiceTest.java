/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link PushService}.
///
/// @since 2.6.4
/// @author Kwaite
class PushServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private PushService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    service = new PushService(mock(ConfigService.class), jdbc, logAdminService);
    service.setSelf(service);
  }

  private static RequeteAbonnement abonnementValide() {
    return new RequeteAbonnement("https://exemple.test/push", new ClesAbonnement("p256dh", "auth"));
  }

  @Test
  void abonner_endpointManquant_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> service.abonner("u1", new RequeteAbonnement(null, new ClesAbonnement("a", "b"))));
  }

  @Test
  void abonner_capNonAtteint_insereEtLogue() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(9);

    service.abonner("u1", abonnementValide());

    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO push_abonnements"), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("push"), anyString(), anyMap());
  }

  // #827 : le cap de 10 abonnements doit être respecté même si le COUNT et l'INSERT sont deux
  // requêtes distinctes — la vérification passe par une transaction SERIALIZABLE dédiée
  // (abonnerTransactionnel), pas testable directement ici (pas de vrai gestionnaire de
  // transactions en test unitaire), mais le comportement observable (rejet au cap) est vérifié.
  @Test
  void abonner_capAtteint_leveErreurMetier429SansInserer() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(10);

    ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.abonner("u1", abonnementValide()));

    assertEquals(429, erreur.getStatus());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO push_abonnements"), anyMap());
  }
}
