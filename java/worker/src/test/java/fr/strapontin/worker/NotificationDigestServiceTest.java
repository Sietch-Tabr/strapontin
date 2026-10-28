/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.resend.Resend;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.core.ClientWebPush;
import fr.strapontin.core.FournisseurBouncyCastle;
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
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires de [NotificationDigestService] avec mocks Mockito.
 *
 * @since 2.0.1
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class NotificationDigestServiceTest {

  // Hors plage silencieuse : ces tests vérifient l'envoi, pas la rétention de nuit.
  private static final java.time.Clock HORLOGE_JOURNEE =
      java.time.Clock.fixed(
          java.time.Instant.parse("2026-10-17T12:00:00Z"), java.time.ZoneOffset.UTC);

  static {
    // Le provider BouncyCastle est normalement enregistré par WorkerApplication#main au
    // démarrage réel — jamais exécuté dans ce test unitaire pur, alors que la construction
    // réelle d'un Notification (chemin nominal push) en a besoin pour parser p256dh.
    FournisseurBouncyCastle.garantir();
  }

  @Mock private ConfigServiceWorker configService;
  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private LogsAdminWorker logsAdminWorker;
  @Mock private fr.strapontin.worker.quota.GestionQuotaResendWorker gestionQuotaResendWorker;

  private NotificationDigestService service;

  @BeforeEach
  void init() {
    lenient().when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(true);
    service =
        new NotificationDigestService(
            configService, jdbc, new ObjectMapper(), logsAdminWorker, gestionQuotaResendWorker);
  }

  // push totalement ignoré (VAPID absent) — aucun appel tenté, aucune remontée de bilan.
  @Test
  void executerDigest_vapidAbsent_nInsereAucunBilanPush() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());

    service.executerDigest("quotidien");

    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), any());
  }

  @Test
  void executerDigest_sansMatches_nAppellePasConfigService() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    service.executerDigest("quotidien");

    verify(configService, never()).lireConfig(eq("RESEND_API_KEY"));
    verify(configService, never()).lireConfig(eq("VAPID_PUBLIC_KEY"));
  }

  @Test
  void executerDigest_resendAbsent_emailIgnore() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    assertDoesNotThrow(() -> service.executerDigest("quotidien"));

    verify(configService, atLeastOnce()).lireConfig("RESEND_API_KEY");
  }

  @Test
  void executerDigest_vapidAbsent_pushIgnore() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneTest("u2")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    assertDoesNotThrow(() -> service.executerDigest("quotidien"));

    verify(configService, atLeastOnce()).lireConfig("VAPID_PUBLIC_KEY");
  }

  @Test
  void construirePayloadPush_unConcert_contientArtisteEtSalle() throws Exception {
    final var concert =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            "https://example.com",
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            null);

    final String payload = invoquerPayloadPush(List.of(concert), "https://strapontin.exemple.fr");

    assertTrue(payload.contains("Stromae"), "Le payload push doit contenir l'artiste");
    assertTrue(payload.contains("Olympia"), "Le payload push doit contenir la salle");
    assertTrue(payload.contains("\"title\""), "Le payload push doit avoir un champ title");
    assertTrue(payload.contains("\"body\""), "Le payload push doit avoir un champ body");
    assertTrue(
        payload.contains("/?ancre=artiste-stromae"), "L'URL push doit pointer sur l'ancre accueil");
  }

  @Test
  void construirePayloadPush_plusieursConcerts_contientNombre() throws Exception {
    final var c1 =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            null);
    final var c2 =
        new NotificationDigestService.ConcertNotif(
            "Aya Nakamura",
            "2026-10-01",
            "Accor Arena",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "aya nakamura",
            "ARTISTE",
            null);

    final String payload = invoquerPayloadPush(List.of(c1, c2), "https://strapontin.exemple.fr");

    assertTrue(
        payload.contains("2 nouveaux concerts"), "Le titre doit indiquer le nombre de concerts");
  }

  @Test
  void construirePayloadPush_plusieursConcertsAvecPrevente_mentionnePreventeSurLaLigneConcernee()
      throws Exception {
    final var enPrevente =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            "presale");
    final var enVente =
        new NotificationDigestService.ConcertNotif(
            "Aya Nakamura",
            "2026-10-01",
            "Accor Arena",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "aya nakamura",
            "ARTISTE",
            "onsale");

    final String payload =
        invoquerPayloadPush(List.of(enPrevente, enVente), "https://strapontin.exemple.fr");

    assertTrue(
        payload.contains("Stromae · mardi 15 septembre 2026 · Prévente"),
        "La ligne du concert en prévente doit mentionner Prévente");
    assertFalse(
        payload.contains("Aya Nakamura · jeudi 1 octobre 2026 · Prévente"),
        "La ligne du concert déjà en vente ne doit pas mentionner Prévente");
  }

  @Test
  void construirePayloadPush_changementArtiste_insereLigneVideDeSeparation() throws Exception {
    final var c1 =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            null);
    final var c2 =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-20",
            "Accor Arena",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            null);
    final var c3 =
        new NotificationDigestService.ConcertNotif(
            "Aya Nakamura",
            "2026-10-01",
            "Zénith Paris",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "aya nakamura",
            "ARTISTE",
            null);

    final String payload =
        invoquerPayloadPush(List.of(c1, c2, c3), "https://strapontin.exemple.fr");
    final String corps = payload.split("\"body\":\"")[1].split("\",\"icon\"")[0];

    assertEquals(1, corps.split("\\\\n\\\\n", -1).length - 1, "Une seule rupture entre artistes");
  }

  @Test
  void construirePayloadPush_concertDeSalle_urlPointeVersSpectacles() throws Exception {
    final var concert =
        new NotificationDigestService.ConcertNotif(
            "Divers",
            "2026-11-10",
            "Olympia",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "divers",
            "SALLE",
            null);

    final String payload = invoquerPayloadPush(List.of(concert), "https://strapontin.exemple.fr");

    assertTrue(
        payload.contains("/spectacles?source=salles"),
        "L'URL push d'un concert de salle doit pointer vers /spectacles?source=salles");
  }

  // l'URL absolue (préfixée par baseUrl) cassait router.push côté service worker — les
  // listeners main.js/App.vue passent l'URL telle quelle, qui ne matche aucune route déclarée
  // avec un schéma https:// en préfixe. icon reste préfixé par baseUrl (résolu par le SW dans les
  // deux cas), seule url doit désormais être un chemin relatif.
  @Test
  void construirePayloadPush_urlEstRelativeMemeAvecBaseUrlConfiguree() throws Exception {
    final var concert =
        new NotificationDigestService.ConcertNotif(
            "Stromae",
            "2026-09-15",
            "Olympia",
            "Paris",
            "https://example.com",
            UUID.randomUUID().toString(),
            "stromae",
            "ARTISTE",
            null);

    final String payload = invoquerPayloadPush(List.of(concert), "https://strapontin.exemple.fr");

    assertTrue(payload.contains("\"url\":\"/?ancre=artiste-stromae\""), payload);
    assertFalse(
        payload.contains("\"url\":\"https://strapontin.exemple.fr"),
        "url ne doit plus être préfixée par baseUrl : " + payload);
    assertTrue(
        payload.contains("\"icon\":\"https://strapontin.exemple.fr/images/icon-192.png\""),
        "icon doit rester préfixée par baseUrl : " + payload);
  }

  @Test
  void construireCorpsEmail_formatHtml_contientArtiste() throws Exception {
    final var concert =
        new NotificationDigestService.ConcertNotif(
            "Indochine",
            "2026-08-20",
            "Zénith Paris",
            "Paris",
            "https://example.com",
            UUID.randomUUID().toString(),
            "indochine",
            "ARTISTE",
            null);

    final String html =
        invoquerCorpsEmail("1 nouveau concert", List.of(concert), "https://strapontin.exemple.fr");

    assertTrue(html.contains("<!DOCTYPE html>"), "L'email doit être du HTML valide");
    assertTrue(html.contains("Indochine"), "L'email doit contenir l'artiste");
    assertTrue(html.contains("Zénith Paris"), "L'email doit contenir la salle");
    assertTrue(html.contains("https://example.com"), "L'email doit contenir le lien billet");
  }

  @Test
  void construireCorpsEmail_changementArtiste_ajouteRuptureVisuelle() throws Exception {
    final var c1 =
        new NotificationDigestService.ConcertNotif(
            "Daft Punk",
            "2026-09-15",
            "Bercy",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "daft punk",
            "ARTISTE",
            null);
    final var c2 =
        new NotificationDigestService.ConcertNotif(
            "Téléphone",
            "2026-10-01",
            "Zénith Paris",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "telephone",
            "ARTISTE",
            null);

    final String html =
        invoquerCorpsEmail("2 nouveaux concerts", List.of(c1, c2), "https://strapontin.exemple.fr");

    assertEquals(
        1,
        html.split("border-top:2px solid #ddd", -1).length - 1,
        "Une seule rupture visuelle doit être ajoutée, au changement d'artiste");
  }

  @Test
  void construireCorpsEmail_memeArtiste_neContientAucuneRuptureVisuelle() throws Exception {
    final var c1 =
        new NotificationDigestService.ConcertNotif(
            "Daft Punk",
            "2026-09-15",
            "Bercy",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "daft punk",
            "ARTISTE",
            null);
    final var c2 =
        new NotificationDigestService.ConcertNotif(
            "Daft Punk",
            "2026-10-01",
            "AccorHotels Arena",
            "Paris",
            null,
            UUID.randomUUID().toString(),
            "daft punk",
            "ARTISTE",
            null);

    final String html =
        invoquerCorpsEmail("2 nouveaux concerts", List.of(c1, c2), "https://strapontin.exemple.fr");

    assertFalse(html.contains("border-top:2px solid #ddd"));
  }

  // Sans service d'envoi configuré, l'email n'est pas un canal joignable : ce n'est pas un
  // échec d'envoi, aucun avertissement répété à chaque cycle.
  @Test
  void executerDigest_resendAbsent_aucunAvertissementDEchec() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    lenient().when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    lenient().when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    final Logger logger = (Logger) LoggerFactory.getLogger(NotificationDigestService.class);
    final ListAppender<ILoggingEvent> evenements = new ListAppender<>();
    evenements.start();
    logger.addAppender(evenements);
    try {
      service.executerDigest("quotidien");
    } finally {
      logger.detachAppender(evenements);
    }

    assertEquals(
        List.of(),
        evenements.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList());
  }

  // --- : marquage notification_envoyee_le uniquement si un canal a réellement réussi ---

  @Test
  void executerDigest_toutEchoue_neMarqueAucunConcertNotifie() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneTest("u3")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());

    service.executerDigest("quotidien");

    verify(jdbc, never())
        .update(contains("notification_envoyee_le"), any(SqlParameterSource.class));
  }

  // Resend/ClientWebPush n'étaient pas injectables (instanciation en dur dans
  // envoyerEmail/envoyerPush), rendant le chemin nominal de succès impossible à exercer.
  @Test
  void executerDigest_emailEnvoyeAvecSucces_marqueLeConcertNotifie() throws Exception {
    when(jdbc.queryForList(contains("FROM user_matches"), anyMap()))
        .thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

    final Resend resendMock = mock(Resend.class);
    final Emails emailsMock = mock(Emails.class);
    when(resendMock.emails()).thenReturn(emailsMock);

    final NotificationDigestService serviceAvecFabriques =
        new NotificationDigestService(
            configService,
            jdbc,
            new ObjectMapper(),
            logsAdminWorker,
            gestionQuotaResendWorker,
            _ -> resendMock,
            (_, _, _) -> {
              throw new IllegalArgumentException("VAPID non configuré en test");
            });

    serviceAvecFabriques.executerDigest("quotidien");

    verify(emailsMock).send(any(CreateEmailOptions.class));
    verify(jdbc).update(contains("notification_envoyee_le"), any(SqlParameterSource.class));
  }

  // La règle du projet interdit toute donnée personnelle dans les journaux : le compte est
  // identifié par son identifiant, jamais par son adresse.
  @Test
  void executerDigest_emailEnvoye_leJournalIdentifieLeCompteSansSonAdresse() throws Exception {
    when(jdbc.queryForList(contains("FROM user_matches"), anyMap()))
        .thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    final Resend resendMock = mock(Resend.class);
    when(resendMock.emails()).thenReturn(mock(Emails.class));
    final NotificationDigestService serviceAvecFabriques =
        new NotificationDigestService(
            configService,
            jdbc,
            new ObjectMapper(),
            logsAdminWorker,
            gestionQuotaResendWorker,
            _ -> resendMock,
            (_, _, _) -> {
              throw new IllegalArgumentException("VAPID non configuré en test");
            });
    final Logger logger = (Logger) LoggerFactory.getLogger(NotificationEmailDigestService.class);
    final ListAppender<ILoggingEvent> evenements = new ListAppender<>();
    evenements.start();
    logger.addAppender(evenements);
    try {
      serviceAvecFabriques.executerDigest("quotidien");
    } finally {
      logger.detachAppender(evenements);
    }

    final List<String> messages =
        evenements.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertTrue(messages.stream().anyMatch(m -> m.contains("u1")));
    assertTrue(messages.stream().noneMatch(m -> m.contains("@")));
  }

  // quota Resend atteint — le canal email doit être ignoré sans jamais contacter la
  // fabrique Resend, comme le fait déjà l'absence de RESEND_API_KEY.
  @Test
  void executerDigest_quotaResendAtteint_ignoreLeCanalEmailSansContacterResend() {
    when(jdbc.queryForList(contains("FROM user_matches"), anyMap()))
        .thenReturn(List.of(ligneTest("u1")));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test123"));
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    when(gestionQuotaResendWorker.reserverEnvoi()).thenReturn(false);

    service.executerDigest("quotidien");

    verify(jdbc, never())
        .update(contains("notification_envoyee_le"), any(SqlParameterSource.class));
  }

  @Test
  void executerDigest_pushEnvoyeAvecSucces_marqueLeConcertNotifie() throws Exception {
    when(jdbc.queryForList(contains("FROM user_matches"), anyMap()))
        .thenReturn(List.of(ligneTest("u2")));
    final Map<String, Object> abonnement = new HashMap<>();
    abonnement.put("id", 1);
    abonnement.put("endpoint", "https://fcm.googleapis.com/fcm/send/abonnement-xyz");
    // Vraie clé publique EC P-256 (point non compressé, base64url) — le constructeur
    // Notification(Subscription, String) parse réellement p256dh en ECPublicKey, une valeur
    // arbitraire lève InvalidKeySpecException avant même l'appel à pushService.send().
    abonnement.put(
        "p256dh",
        "BPKi4XnOYaPYQWcKZsUfMxTQZV0JyJA-zx65BGYqeHJ8KDlO2AL5lhoFUCXC1iFMUlTzt_sgos9GiYMxFEgCDQE");
    abonnement.put("auth", "RZIK8q0lRcIbLO9VyII_4Q");
    when(jdbc.queryForList(contains("push_abonnements"), anyMap())).thenReturn(List.of(abonnement));
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.update(contains("echecs_consecutifs = 0"), anyMap())).thenReturn(1);

    final ClientWebPush pushServiceMock = mock(ClientWebPush.class);
    when(pushServiceMock.envoyer(any(), any(), any(), any()))
        .thenReturn(new ClientWebPush.Reponse(201, ""));

    final NotificationDigestService serviceAvecFabriques =
        new NotificationDigestService(
            configService,
            jdbc,
            new ObjectMapper(),
            logsAdminWorker,
            gestionQuotaResendWorker,
            new NotificationDigestService.Fabriques(
                _ -> {
                  throw new IllegalStateException("Resend non configuré en test");
                },
                (_, _, _) -> pushServiceMock,
                HORLOGE_JOURNEE));

    serviceAvecFabriques.executerDigest("quotidien");

    verify(pushServiceMock).envoyer(any(), any(), any(), any());
    verify(jdbc).update(contains("notification_envoyee_le"), any(SqlParameterSource.class));
  }

  // « Jamais » coupe aussi le push : seul l'email était coupé, contrairement à l'interface.
  @Test
  void executerDigest_frequenceJamais_nEnvoieAucunPush() throws Exception {
    final Map<String, Object> ligne = ligneTest("u3");
    ligne.put("frequence_notifications", "jamais");
    when(jdbc.queryForList(contains("FROM user_matches"), anyMap())).thenReturn(List.of(ligne));
    // Push configuré et appareil abonné : lus seulement si un canal est actif.
    final Map<String, Object> abonnement = new HashMap<>();
    abonnement.put("id", 1);
    abonnement.put("endpoint", "https://fcm.googleapis.com/fcm/send/abonnement-jamais");
    abonnement.put(
        "p256dh",
        "BPKi4XnOYaPYQWcKZsUfMxTQZV0JyJA-zx65BGYqeHJ8KDlO2AL5lhoFUCXC1iFMUlTzt_sgos9GiYMxFEgCDQE");
    abonnement.put("auth", "RZIK8q0lRcIbLO9VyII_4Q");
    lenient()
        .when(jdbc.queryForList(contains("push_abonnements"), anyMap()))
        .thenReturn(List.of(abonnement));
    lenient()
        .when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    lenient().when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());
    lenient()
        .when(configService.lireConfig("VAPID_PUBLIC_KEY"))
        .thenReturn(Optional.of("cle-publique"));
    lenient()
        .when(configService.lireConfig("VAPID_PRIVATE_KEY"))
        .thenReturn(Optional.of("cle-privee"));
    final ClientWebPush pushServiceMock = mock(ClientWebPush.class);

    new NotificationDigestService(
            configService,
            jdbc,
            new ObjectMapper(),
            logsAdminWorker,
            gestionQuotaResendWorker,
            new NotificationDigestService.Fabriques(
                _ -> {
                  throw new IllegalStateException("Resend non configuré en test");
                },
                (_, _, _) -> pushServiceMock,
                HORLOGE_JOURNEE))
        .executerDigest("quotidien");

    verify(pushServiceMock, never()).envoyer(any(), any(), any(), any());
  }

  private static Map<String, Object> ligneTest(final String userId) {
    final Map<String, Object> ligne = new HashMap<>();
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

  private static String invoquerPayloadPush(
      final List<NotificationDigestService.ConcertNotif> concerts, final String baseUrl)
      throws Exception {
    final Method m =
        NotificationPushDigestService.class.getDeclaredMethod(
            "construirePayloadPush", List.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, concerts, baseUrl);
  }

  private static String invoquerCorpsEmail(
      final String sujet,
      final List<NotificationDigestService.ConcertNotif> concerts,
      final String baseUrl)
      throws Exception {
    final Method m =
        EmailDigestTemplate.class.getDeclaredMethod(
            "construireCorpsEmail", String.class, List.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, sujet, concerts, baseUrl);
  }

  @Test
  void etiquetteStatut_statutNotable_retourneLeLibelle() {
    assertEquals("Prévente", NotificationDigestService.etiquetteStatut("presale"));
    assertEquals("Date reportée", NotificationDigestService.etiquetteStatut("rescheduled"));
    assertEquals("", NotificationDigestService.etiquetteStatut("onsale"));
    assertEquals("", NotificationDigestService.etiquetteStatut(null));
  }
}
