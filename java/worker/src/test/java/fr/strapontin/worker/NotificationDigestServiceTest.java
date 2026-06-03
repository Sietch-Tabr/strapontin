/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires de [NotificationDigestService] avec mocks Mockito.
///
/// @since 2.0.1
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class NotificationDigestServiceTest {

  @Mock private ConfigServiceWorker configService;
  @Mock private NamedParameterJdbcTemplate jdbc;

  private NotificationDigestService service;

  @BeforeEach
  void init() {
    service = new NotificationDigestService(configService, jdbc, new ObjectMapper());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerDigest_sansMatches_nAppellePasConfigService() {
    when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of());

    service.executerDigest("quotidien");

    verify(configService, never()).lireConfig(eq("RESEND_API_KEY"));
    verify(configService, never()).lireConfig(eq("VAPID_PUBLIC_KEY"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerDigest_resendAbsent_emailIgnore() {
    when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);

    assertDoesNotThrow(() -> service.executerDigest("quotidien"));

    verify(configService).lireConfig("RESEND_API_KEY");
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerDigest_vapidAbsent_pushIgnore() {
    when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(ligneTest("u2")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);

    assertDoesNotThrow(() -> service.executerDigest("quotidien"));

    verify(configService).lireConfig("VAPID_PUBLIC_KEY");
  }

  @Test
  void construirePayloadPush_unConcert_contientArtisteEtSalle() throws Exception {
    var concert =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            "https://example.com",
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE");

    String payload = invoquerPayloadPush(List.of(concert), "https://strapontin.kwaite.fr");

    assertTrue(payload.contains("Stromae"), "Le payload push doit contenir l'artiste");
    assertTrue(payload.contains("Olympia"), "Le payload push doit contenir la salle");
    assertTrue(payload.contains("\"title\""), "Le payload push doit avoir un champ title");
    assertTrue(payload.contains("\"body\""), "Le payload push doit avoir un champ body");
    assertTrue(
        payload.contains("/#artiste-stromae"), "L'URL push doit pointer sur l'ancre accueil");
  }

  @Test
  void construirePayloadPush_plusieursConcerts_contientNombre() throws Exception {
    var c1 =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE");
    var c2 =
        new NotificationDigestService.ConcertNotif(
            "Aya Nakamura",
            "2026-10-01",
            "Accor Arena",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "aya nakamura",
            "ARTISTE");

    String payload = invoquerPayloadPush(List.of(c1, c2), "https://strapontin.kwaite.fr");

    assertTrue(
        payload.contains("2 nouveaux concerts"), "Le titre doit indiquer le nombre de concerts");
  }

  @Test
  void construirePayloadPush_concertDeSalle_urlPointeVersSpectacles() throws Exception {
    var concert =
        new NotificationDigestService.ConcertNotif(
            "Divers",
            "2026-11-10",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "divers",
            "SALLE");

    String payload = invoquerPayloadPush(List.of(concert), "https://strapontin.kwaite.fr");

    assertTrue(
        payload.contains("/spectacles?source=salles"),
        "L'URL push d'un concert de salle doit pointer vers /spectacles?source=salles");
  }

  @Test
  void construireCorpsEmail_formatHtml_contientArtiste() throws Exception {
    var concert =
        new NotificationDigestService.ConcertNotif(
            "Indochine",
            "2026-08-20",
            "Zénith Paris",
            "Paris",
            "https://example.com",
            UUID.randomUUID().toString(),
            "indochine",
            "ARTISTE");

    String html =
        invoquerCorpsEmail("1 nouveau concert", List.of(concert), "https://strapontin.kwaite.fr");

    assertTrue(html.contains("<!DOCTYPE html>"), "L'email doit être du HTML valide");
    assertTrue(html.contains("Indochine"), "L'email doit contenir l'artiste");
    assertTrue(html.contains("Zénith Paris"), "L'email doit contenir la salle");
    assertTrue(html.contains("https://example.com"), "L'email doit contenir le lien billet");
  }

  private static Map<String, Object> ligneTest(String userId) {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("user_id", userId);
    ligne.put("email", userId + "@test.fr");
    ligne.put("concert_id", UUID.randomUUID().toString());
    ligne.put("artiste", "Artiste Test");
    ligne.put("date", "2026-09-15");
    ligne.put("salle", "Salle Test");
    ligne.put("ville", "Paris");
    ligne.put("url", null);
    ligne.put("artiste_norm", "artiste test");
    ligne.put("source", "ARTISTE");
    ligne.put("notif_artistes", true);
    ligne.put("notif_salles", false);
    ligne.put("notif_artistes_push", true);
    ligne.put("notif_salles_push", false);
    return ligne;
  }

  @SuppressWarnings("unchecked")
  private static String invoquerPayloadPush(
      List<NotificationDigestService.ConcertNotif> concerts, String baseUrl) throws Exception {
    Method m =
        NotificationDigestService.class.getDeclaredMethod(
            "construirePayloadPush", List.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, concerts, baseUrl);
  }

  @SuppressWarnings("unchecked")
  private static String invoquerCorpsEmail(
      String sujet, List<NotificationDigestService.ConcertNotif> concerts, String baseUrl)
      throws Exception {
    Method m =
        NotificationDigestService.class.getDeclaredMethod(
            "construireCorpsEmail", String.class, List.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, sujet, concerts, baseUrl);
  }
}
