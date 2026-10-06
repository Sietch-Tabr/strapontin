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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link NotificationPushService} — focus sur le ciblage d'abonnement de
 * {@code envoyerNotificationTest}.
 *
 * @since 2.6.4
 * @author Kwaite
 */
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
  void envoyerNotificationTest_vapidNonConfigure_serviceNonConfigureSansRequete() {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());

    final ResultatEnvoiPush resultat = service.envoyerNotificationTest("u1", null);

    assertEquals(ResultatEnvoiPush.SERVICE_NON_CONFIGURE, resultat);
    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  // endpoint fourni → cible précisément cet abonnement plutôt que le plus récent par id.
  @Test
  @SuppressWarnings("unchecked")
  void envoyerNotificationTest_avecEndpoint_requeteFiltreParEndpoint() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final ResultatEnvoiPush resultat =
        service.envoyerNotificationTest("u1", "https://exemple.test/endpoint-precis");

    assertEquals(ResultatEnvoiPush.APPAREIL_INCONNU, resultat);
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
    assertFalse(sqlCaptor.getValue().contains("ORDER BY id DESC"));
    assertTrue(sqlCaptor.getValue().contains("endpoint = :endpoint"));
    assertEquals("https://exemple.test/endpoint-precis", paramsCaptor.getValue().get("endpoint"));
  }

  // endpoint absent → repli sur le plus récent (comportement historique conservé).
  @Test
  void envoyerNotificationTest_sansEndpoint_requeteReplieSurLePlusRecent() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final ResultatEnvoiPush resultat = service.envoyerNotificationTest("u1", null);

    assertEquals(ResultatEnvoiPush.APPAREIL_INCONNU, resultat);
    final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sqlCaptor.capture(), anyMap());
    assertTrue(sqlCaptor.getValue().contains("ORDER BY id DESC"));
    assertTrue(sqlCaptor.getValue().contains("LIMIT 1"));
  }

  // Un abonnement enregistré avant la validation des endpoints n'est jamais appelé : il est
  // supprimé au premier envoi.
  @Test
  void envoyerNotificationTest_abonnementHorsServicesDePush_supprimeSansEnvoyer() {
    final Map<String, Object> ab = new HashMap<>();
    ab.put("id", UUID.randomUUID());
    ab.put("endpoint", "http://127.0.0.1:8081/trigger/tout");
    ab.put("p256dh", "BPk");
    ab.put("auth", "au");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ab));

    final ResultatEnvoiPush resultat = service.envoyerNotificationTest("u1", null);

    assertEquals(ResultatEnvoiPush.SERVICE_NAVIGATEUR_REFUSE, resultat);
    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
  }
}
