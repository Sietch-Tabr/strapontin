/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.VapidKeyGenerator;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Envoi push réel depuis le module web : vraies clés VAPID, chargement des
 * clés, chiffrement du contenu et signature par le vrai {@link PushService} ;
 * seul l'appel HTTP au service de push du navigateur est intercepté.
 * @since 3.0.3
 * @author Kwaite
 */
class NotificationPushEnvoiReelTest {

  private ConfigService configService;
  private NamedParameterJdbcTemplate jdbc;
  private final List<HttpPost> requetesEmises = new ArrayList<>();
  private int statutRenvoye;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    // Le défaut d'origine ne se voyait que sans fournisseur déjà enregistré.
    Security.removeProvider("BC");
    configService = mock(ConfigService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    final VapidKeyGenerator.PaireVapid vapid = VapidKeyGenerator.genererPaire();
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of(vapid.clePublique()));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of(vapid.clePrivee()));
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("contact@exemple.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.of("https://exemple.fr"));
    statutRenvoye = 201;
  }

  private NotificationPushService service() {
    return new NotificationPushService(configService, jdbc, mock(LogAdminService.class)) {
      @Override
      PushService creerServicePush(
          final String clePublique, final String clePrivee, final String sujet)
          throws GeneralSecurityException {
        return new PushService(clePublique, clePrivee, sujet) {
          @Override
          public HttpResponse send(final Notification notification, final Encoding encodage) {
            try {
              // L'encodage demandé par le service : send(Notification) seul retombe sur l'ancien
              // « aesgcm », que Firebase refuse (« crypto-key header had invalid format »).
              requetesEmises.add(preparePost(notification, encodage));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return new BasicHttpResponse(HttpVersion.HTTP_1_1, statutRenvoye, "");
          }
        };
      }
    };
  }

  private static Map<String, Object> abonnement() throws GeneralSecurityException {
    final byte[] auth = new byte[16];
    new SecureRandom().nextBytes(auth);
    final Map<String, Object> ab = new HashMap<>();
    ab.put("id", UUID.randomUUID());
    ab.put("user_id", "u1");
    ab.put("endpoint", "https://fcm.googleapis.com/fcm/send/appareil-de-test");
    ab.put("p256dh", VapidKeyGenerator.genererPaire().clePublique());
    ab.put("auth", Base64.getUrlEncoder().withoutPadding().encodeToString(auth));
    return ab;
  }

  @Test
  void envoyerNotificationTest_vraiesCles_requeteChiffreeEtSigneeEmise() throws Exception {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnement()));

    final ResultatEnvoiPush resultat = service().envoyerNotificationTest("u1", null);

    assertEquals(ResultatEnvoiPush.ENVOYEE, resultat);
    assertEquals(1, requetesEmises.size());
    final HttpPost requete = requetesEmises.get(0);
    assertEquals("aes128gcm", requete.getFirstHeader("Content-Encoding").getValue());
    assertNotNull(requete.getFirstHeader("Authorization"));
    assertTrue(requete.getFirstHeader("Authorization").getValue().startsWith("vapid t="));
  }

  @Test
  void envoyerNotificationTest_navigateurRetireLAbonnement_appareilRevoqueEtSupprime()
      throws Exception {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnement()));
    statutRenvoye = 410;

    final ResultatEnvoiPush resultat = service().envoyerNotificationTest("u1", null);

    assertEquals(ResultatEnvoiPush.APPAREIL_REVOQUE, resultat);
    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
  }

  @Test
  void envoyerNotificationTest_serviceDePushEnErreur_echecEnvoi() throws Exception {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnement()));
    statutRenvoye = 503;

    assertEquals(ResultatEnvoiPush.ECHEC_ENVOI, service().envoyerNotificationTest("u1", null));
  }

  @Test
  void envoyerNotificationTest_cinquiemeEchecConsecutif_appareilRevoque() throws Exception {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnement()));
    when(jdbc.queryForMap(contains("echecs_consecutifs + 1"), any(SqlParameterSource.class)))
        .thenReturn(Map.of("echecs_consecutifs", 5, "serie_assez_longue", true));
    statutRenvoye = 403;

    assertEquals(ResultatEnvoiPush.APPAREIL_REVOQUE, service().envoyerNotificationTest("u1", null));
    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(jdbc).update(contains("cause_dernier_retrait_appareil"), anyMap());
  }

  @Test
  void envoyerNotificationTest_clesIllisibles_serviceNonConfigure() {
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("illisible"));
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("endpoint", "https://fcm.googleapis.com/x")));

    assertEquals(
        ResultatEnvoiPush.SERVICE_NON_CONFIGURE,
        new NotificationPushService(configService, jdbc, mock(LogAdminService.class))
            .envoyerNotificationTest("u1", null));
  }

  @Test
  void envoyerATous_vraiesCles_chaqueAbonnementNotifie() throws Exception {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnement(), abonnement()));

    assertEquals(2, service().envoyerATous("Incident", "Service rétabli"));
    assertEquals(2, requetesEmises.size());
  }
}
