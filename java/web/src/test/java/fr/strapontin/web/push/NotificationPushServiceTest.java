/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link NotificationPushService} — focus sur le ciblage d'abonnement de
/// {@code envoyerNotificationTest} (#826).
///
/// @since 2.6.4
/// @author Kwaite
class NotificationPushServiceTest {

  private ConfigService configService;
  private NamedParameterJdbcTemplate jdbc;
  private NotificationPushService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new NotificationPushService(configService, jdbc, mock(LogAdminService.class));
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("pub"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("priv"));
  }

  @Test
  void envoyerNotificationTest_vapidNonConfigure_retourneFalseSansRequete() {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());

    boolean resultat = service.envoyerNotificationTest("u1", null);

    assertFalse(resultat);
    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  // #826 : endpoint fourni → cible précisément cet abonnement plutôt que le plus récent par id.
  @Test
  void envoyerNotificationTest_avecEndpoint_requeteFiltreParEndpoint() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    boolean resultat =
        service.envoyerNotificationTest("u1", "https://exemple.test/endpoint-precis");

    assertFalse(resultat);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
    assertFalse(sqlCaptor.getValue().contains("ORDER BY id DESC"));
    assertTrue(sqlCaptor.getValue().contains("endpoint = :endpoint"));
    assertEquals("https://exemple.test/endpoint-precis", paramsCaptor.getValue().get("endpoint"));
  }

  // #826 : endpoint absent → repli sur le plus récent (comportement historique conservé).
  @Test
  void envoyerNotificationTest_sansEndpoint_requeteReplieSurLePlusRecent() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    boolean resultat = service.envoyerNotificationTest("u1", null);

    assertFalse(resultat);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sqlCaptor.capture(), anyMap());
    assertTrue(sqlCaptor.getValue().contains("ORDER BY id DESC"));
    assertTrue(sqlCaptor.getValue().contains("LIMIT 1"));
  }
}
