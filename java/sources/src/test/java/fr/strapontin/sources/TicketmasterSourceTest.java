/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.QuotaTM;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TicketmasterSourceTest {

  // --- Fixtures ---

  private static Map<String, Object> evenementTMRaw() {
    return evenementTMRaw(Map.of());
  }

  private static Map<String, Object> evenementTMRaw(final Map<String, Object> surcharge) {
    final Map<String, Object> base = new HashMap<>();
    base.put("name", "Julien Clerc — Tournée 2025");
    base.put(
        "dates",
        Map.of(
            "start", Map.of("localDate", "2025-09-15", "localTime", "20:30:00"),
            "status", Map.of("code", "onsale")));
    base.put(
        "_embedded",
        Map.of(
            "attractions", List.of(Map.of("name", "Julien Clerc")),
            "venues",
                List.of(
                    Map.of(
                        "name", "ACCOR ARENA",
                        "city", Map.of("name", "Paris"),
                        "postalCode", "75012"))));
    base.put(
        "classifications",
        List.of(
            Map.of(
                "segment", Map.of("name", "Music"),
                "genre", Map.of("name", "Pop"))));
    base.put("priceRanges", List.of(Map.of("min", 39.9, "max", 99.9)));
    base.put("url", "https://example.com");
    base.putAll(surcharge);
    return base;
  }

  private static String reponsePageTMJson(final int totalElements, final boolean avecEvents) {
    final String events =
        avecEvents
            ? """
            [{"name":"Julien Clerc — Tournée 2025",
              "dates":{"start":{"localDate":"2025-09-15","localTime":"20:30:00"},"status":{"code":"onsale"}},
              "_embedded":{"attractions":[{"name":"Julien Clerc"}],
                "venues":[{"name":"ACCOR ARENA","city":{"name":"Paris"},"postalCode":"75012"}]},
              "classifications":[{"segment":{"name":"Music"},"genre":{"name":"Pop"}}],
              "priceRanges":[{"min":39.9,"max":99.9}],
              "url":"https://example.com"}]
            """
            : "[]";
    final int totalPages = (int) Math.ceil((double) totalElements / 200);
    return """
    {"_embedded":{"events":%s},"page":{"totalElements":%d,"totalPages":%d}}
    """
        .formatted(events, totalElements, totalPages);
  }

  // --- parseDateTM ---

  @Test
  void parseDateTM_dateEtHeureValides_retourneDateEtHeure() {
    final String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", "20:30:00");
    assertEquals("2025-09-15", resultat[0]);
    assertEquals("20:30", resultat[1]);
  }

  @Test
  void parseDateTM_heureAbsente_retourneHeureNull() {
    final String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", null);
    assertEquals("2025-09-15", resultat[0]);
    assertNull(resultat[1]);
  }

  @Test
  void parseDateTM_heure0000_retourneHeureNull() {
    final String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", "00:00:00");
    assertNull(resultat[1]);
  }

  @Test
  void parseDateTM_dateAvecHeure_tronqueA10Caracteres() {
    final String[] resultat = TicketmasterSource.parseDateTM("2025-09-15T20:30:00Z", "20:30:00");
    assertEquals("2025-09-15", resultat[0]);
    assertEquals("20:30", resultat[1]);
  }

  // --- mapEvenement ---

  @Test
  void mapEvenement_evenementComplet_parseTousLesChamps() {
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
    assertEquals("2025-09-15", resultat.date());
    assertEquals("20:30", resultat.heure());
    assertEquals("Accor Arena", resultat.salle());
    assertEquals("Paris", resultat.ville());
    assertEquals("75", resultat.dept());
    assertEquals("https://example.com", resultat.url());
    assertEquals("ticketmaster", resultat.source());
    assertEquals("music", resultat.classification());
    assertEquals("Pop", resultat.genre());
    assertEquals("onsale", resultat.statut());
  }

  @Test
  void mapEvenement_attractionsAbsentes_utiliseFallback() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Zénith Paris",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75019")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "Julien Clerc");
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  @Test
  void mapEvenement_sansAttractionNiFallback_retourneNull() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "Radiohead - OK Computer Tour",
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Zénith Paris",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75019")))));
    assertNull(TicketmasterSource.mapEvenement(evt, null));
  }

  @Test
  void mapEvenement_salleToutMajuscule_normaliseEnTitleCase() {
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
    assertNotNull(resultat);
    assertEquals("Accor Arena", resultat.salle());
  }

  @Test
  void mapEvenement_salleAvecApostrophe_capitaliseApresApostrophe() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Julien Clerc")),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "L'OLYMPIA",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75009")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("L'Olympia", resultat.salle());
  }

  @Test
  void mapEvenement_fauxPositifFallback_rejetteEvenementSansRapport() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "Déjeuner pour 2 personnes Tour Eiffel",
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Madame Brasserie",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75007")))));
    assertNull(TicketmasterSource.mapEvenement(evt, "2 Unlimited"));
  }

  @Test
  void mapEvenement_fallbackTitreContientArtiste_accepteEvenement() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "2 Unlimited - Greatest Hits Tour",
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Zénith Paris",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75019")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "2 Unlimited");
    assertNotNull(resultat);
    assertEquals("2 Unlimited", resultat.artiste());
  }

  @Test
  void mapEvenement_dateAbsente_retourneNull() {
    final Map<String, Object> evt = new HashMap<>(evenementTMRaw());
    evt.put("dates", Map.of("start", Map.of()));
    assertNull(TicketmasterSource.mapEvenement(evt, null));
  }

  @Test
  void mapEvenement_classificationArtsTheatre_retourneTheatre() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "classifications",
                List.of(
                    Map.of(
                        "segment", Map.of("name", "Arts et théâtre"),
                        "genre", Map.of("name", "Théâtre")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("theatre", resultat.classification());
  }

  @Test
  void mapEvenement_urlAbsente_retourneNullPourUrl() {
    final Map<String, Object> evt = new HashMap<>(evenementTMRaw());
    evt.remove("url");
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertNull(resultat.url());
  }

  @Test
  void mapEvenement_DOMGuadeloupe_retourneDept971() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Artiste")),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Salle",
                            "city", Map.of("name", "Pointe-à-Pitre"),
                            "postalCode", "97100")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("971", resultat.dept());
  }

  @Test
  void mapEvenement_venueEtrangerCodePostalCollisionnantAvecDeptFrancais_neposePasDeDept() {
    // Rockhal (Esch-sur-Alzette, Luxembourg) — code postal "4351" collisionne par troncature
    // avec le département français 43 (Haute-Loire).
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Artiste")),
                    "venues",
                    List.of(
                        Map.of(
                            "name",
                            "Rockhal",
                            "city",
                            Map.of("name", "Esch-sur-Alzette"),
                            "postalCode",
                            "4351",
                            "country",
                            Map.of("countryCode", "LU"))))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertNull(
        resultat.dept(), "Une salle hors de France ne doit jamais recevoir de dept français");
  }

  @Test
  void mapEvenement_venueFrancaiseCountryCodeExplicite_poseNormalementLeDept() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Artiste")),
                    "venues",
                    List.of(
                        Map.of(
                            "name",
                            "Zénith Paris",
                            "city",
                            Map.of("name", "Paris"),
                            "postalCode",
                            "75019",
                            "country",
                            Map.of("countryCode", "FR"))))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("75", resultat.dept());
  }

  // --- Tests HTTP ---

  @Test
  void fetchArtiste_reponseValide_retourneConcerts() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchArtiste("Julien Clerc");

    assertEquals(1, resultats.size());
    assertEquals("Julien Clerc", resultats.get(0).artiste());
    serveur.verify();
  }

  @Test
  void fetchArtiste_embeddedAbsent_retourneListeVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(
                    """
                    {"page":{"totalElements":0,"totalPages":0}}
                    """,
                    MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "0"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchArtiste("Artiste Inconnu");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void fetchArtiste_metsAJourLeQuota() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(0, false), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Radiohead");

    final QuotaTM quota = source.obtenirQuota();
    assertNotNull(quota);
    assertEquals(9800, quota.dispo());
    assertEquals(10000, quota.total());
    serveur.verify();
  }

  @Test
  void fetchArtistesLot_listeVide_retourneListeVide() {
    final TicketmasterSource source = new TicketmasterSource("fake-key");
    assertTrue(source.fetchArtistesLot(List.of()).isEmpty());
  }

  @Test
  void fetchArtistesLot_avecIds_retourneConcerts() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchArtistesLot(List.of("tm-123", "tm-456"));

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void accepteIdentifiantSalle_prefixeTm_retourneVrai() {
    final TicketmasterSource source = new TicketmasterSource("fake-key");
    assertTrue(source.accepteIdentifiantSalle("tm:rZFSnyZdFvA"));
    assertFalse(source.accepteIdentifiantSalle("digitick:lempreinte"));
    assertFalse(source.accepteIdentifiantSalle("rZFSnyZdFvA"));
    assertFalse(source.accepteIdentifiantSalle(null));
  }

  @Test
  void fetchSalle_identifiantPrefixe_stripPrefixeEtRetourneConcerts() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchSalle("tm:venue-123");

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void fetchSalle_identifiantBrut_retourneConcerts() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchSalle("venue-123");

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void fetchSalle_aucunEvent_retourneListeVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(0, false), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchSalle("tm:venue-vide");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  // Un échec HTTP (contrairement à une salle réellement sans concert, testé ci-dessus) doit
  // rester distinguable après coup — voir ResultatPage#succes, propagé jusqu'au log de fetchSalle.
  @Test
  void fetchSalle_erreurHttp_retourneListeVideSansLeverDException() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchSalle("tm:venue-en-echec");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_nomTrouve_retourneId() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"attractions":[{"id":"tm-456","name":"Julien Clerc"}]}}
                """,
                MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final String id = source.rechercherAttractionId("Julien Clerc");

    assertEquals("tm-456", id);
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_aucuneCorrespondanceExacteParmis10_retourneNull() {
    // non-régression : TM retourne "Anuv Jain" sans "Jain" parmi 10 résultats → null
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"attractions":[
                  {"id":"K8vZ917jjsV","name":"Anuv Jain"},
                  {"id":"K8vZ917abc","name":"Jain Malhotra"}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final String id = source.rechercherAttractionId("Jain");

    assertNull(id);
    serveur.verify();
  }

  // un nom sans correspondance ne doit pas re-déclencher indéfiniment le même WARN à chaque
  // appel : le cache de throttle retient le nom déjà signalé.
  @Test
  void rechercherAttractionId_aucuneCorrespondance_memoriseNomDansLeCacheDeThrottle() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"attractions":[{"id":"K8vZ917xyz","name":"Furiez"}]}}
                """,
                MediaType.APPLICATION_JSON));

    final Cache<String, Boolean> cache = Caffeine.newBuilder().build();
    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient, cache);
    final String id = source.rechercherAttractionId("Furies");

    assertNull(id);
    assertEquals(Boolean.TRUE, cache.getIfPresent("furies"));
  }

  @Test
  void rechercherAttractionId_correspondanceExacteApresDecalage_retourneId() {
    // non-régression : TM retourne "Shy" avant "Shy'm" (tri popularité) → doit trouver Shy'm
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"attractions":[
                  {"id":"K8vZ917paOf","name":"Shy"},
                  {"id":"K8vZ917shym","name":"Shy'm"}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final String id = source.rechercherAttractionId("Shy'm");

    assertEquals("K8vZ917shym", id);
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_nomNonTrouve_retourneNull() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final String id = source.rechercherAttractionId("Artiste Inconnu");

    assertNull(id);
    serveur.verify();
  }

  @Test
  void fetchRegion_deptInconnu_retourneListeVide() {
    final TicketmasterSource source = new TicketmasterSource("fake-key");
    final List<ConcertBrut> resultats = source.fetchRegion(List.of("ZZ"), null);
    assertTrue(resultats.isEmpty());
  }

  @Test
  void fetchRegion_deptConnu_retourneConcerts() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchRegion(List.of("75"), null);

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void mapEvenement_offsaleAvecVentePubliqueFuture_retourneStatutPresale() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                Map.of(
                    "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                    "status", Map.of("code", "offsale")),
                "sales",
                Map.of("public", Map.of("startDateTime", "2099-01-01T12:00:00Z"))));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("presale", concert.statut());
    assertEquals("2099-01-01T12:00:00Z", concert.presaleDebut());
  }

  @Test
  void rechercherAttractionId_erreurHttp_retourneNull() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(withServerError());

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final String id = source.rechercherAttractionId("Artiste");

    assertNull(id);
    serveur.verify();
  }

  @Test
  void mapEvenement_offsaleAvecVentePubliquePassee_resteOffsale() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                Map.of(
                    "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                    "status", Map.of("code", "offsale")),
                "sales",
                Map.of("public", Map.of("startDateTime", "2020-01-01T12:00:00Z"))));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("offsale", concert.statut());
  }

  @Test
  void mapEvenement_offsaleAvecPresaleFuture_retourneStatutPresale() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                Map.of(
                    "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                    "status", Map.of("code", "offsale")),
                "sales",
                Map.of(
                    "presales",
                    List.of(
                        Map.of(
                            "startDateTime", "2099-01-01T12:00:00Z",
                            "name", "Prévente carte bancaire")))));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("presale", concert.statut());
    assertEquals("2099-01-01T12:00:00Z", concert.presaleDebut());
  }

  @Test
  void mapEvenement_offsaleAvecPresalePassee_resteOffsale() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                Map.of(
                    "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                    "status", Map.of("code", "offsale")),
                "sales",
                Map.of(
                    "presales",
                    List.of(
                        Map.of(
                            "startDateTime", "2020-01-01T12:00:00Z",
                            "name", "Prévente carte bancaire")))));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("offsale", concert.statut());
  }

  @Test
  void mapEvenement_urlEventPackage_neutraliseStatutEtPresale() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "url",
                "https://www.ticketmaster.fr/fr/pack/package-angele-08-mars-billet/idpack/123",
                "sales",
                Map.of(
                    "presales",
                    List.of(Map.of("startDateTime", "2099-01-01T12:00:00Z", "name", "VIP")))));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertNotNull(concert);
    assertNull(concert.statut());
    assertNull(concert.presaleDebut());
    assertNull(concert.presaleNom());
  }

  @Test
  void mapEvenement_urlEventStandard_neNeutralisePasLeStatut() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of("url", "https://www.ticketmaster.fr/fr/billet/angele-08-mars/event/456"));

    final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertNotNull(concert);
    assertEquals("onsale", concert.statut());
  }

  // --- filtrage homonymes / partiels ---

  @Test
  void mapEvenement_fallbackEtAttractionNomDifferent_retourneNull() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Anuv Jain")),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Zénith Paris",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75019")))));
    assertNull(
        TicketmasterSource.mapEvenement(evt, "Jain"),
        "Un concert d'Anuv Jain ne doit pas être retourné lors d'un fetch de Jain");
  }

  @Test
  void mapEvenement_fallbackEtAttractionNomIdentique_accepteEvenement() {
    final Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions",
                    List.of(Map.of("name", "Julien Clerc")),
                    "venues",
                    List.of(
                        Map.of(
                            "name", "Zénith Paris",
                            "city", Map.of("name", "Paris"),
                            "postalCode", "75019")))));
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "Julien Clerc");
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  @Test
  void mapEvenement_fallbackNullAttractionPresente_accepteEvenementSansVerification() {
    // fetchSalle passe null comme fallback — pas de filtrage par nom attendu
    final ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  // --- Tests santé des sources ---

  @Test
  void verifierVenue_reponse200_retourneVivant() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues/rZFSnyZdFvA.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.VIVANT, source.verifierVenue("rZFSnyZdFvA"));
    serveur.verify();
  }

  @Test
  void verifierVenue_reponse404_retourneMort() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues/venueMort.json")))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.MORT, source.verifierVenue("venueMort"));
    serveur.verify();
  }

  @Test
  void verifierAttraction_reponse404_retourneMort() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(
            requestTo(org.hamcrest.Matchers.containsString("/attractions/attractionMorte.json")))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.MORT, source.verifierAttraction("attractionMorte"));
    serveur.verify();
  }

  @Test
  void rechercherVenuesParNom_reponseValide_retourneCandidats() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"venues":[{"id":"rZ7abc","name":"Zenith Nantes"}]}}
                """,
                MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<TicketmasterSource.CandidatVenueTM> candidats =
        source.rechercherVenuesParNom("Zenith Nantes");

    assertEquals(1, candidats.size());
    assertEquals("rZ7abc", candidats.get(0).id());
    serveur.verify();
  }

  @Test
  void rechercherVenuesParNom_embeddedAbsent_retourneListeVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertTrue(source.rechercherVenuesParNom("Inconnue").isEmpty());
    serveur.verify();
  }

  @Test
  void compterEventsVenue_reponseValide_retourneTotal() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(
                """
                {"page":{"totalElements":3}}
                """,
                MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(3, source.compterEventsVenue("rZ7abc"));
    serveur.verify();
  }

  @Test
  void compterEventsVenue_erreurReseau_retourneZero() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(0, source.compterEventsVenue("rZ7abc"));
    serveur.verify();
  }

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "0"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");

    final BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttp_compteUneErreur() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");

    final BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  // Un 429 (Spike Arrest) reste compté comme n'importe quelle autre erreur HTTP dans le bilan —
  // seul le message de log distingue ce cas pour objectiver la latence réseau réelle, sans
  // changer le comportement fonctionnel observable ici.
  @Test
  void preleverBilan_reponse429_compteUneErreurCommeToutAutreEchec() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final List<ConcertBrut> resultats = source.fetchArtiste("Julien Clerc");

    assertTrue(resultats.isEmpty());
    final BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");
    source.preleverBilan();

    final BilanFetch second = source.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }

  // concertsManques reste une métrique TM spécifique, distincte du bilan appels/erreurs commun
  // : pagination plafonnée à NB_PAGES_MAX (6) pages de 200 événements, totalElements=1300
  // déclenche un plafonnement (7 pages nécessaires) laissant 1300 - 6 = 1294 concerts non
  // récupérés (6 pages fetchées, 1 événement par page dans la fixture).
  @Test
  void preleverConcertsManques_paginationPlafonnee_compteLesConcertsNonRecuperes() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    for (int i = 0; i < 6; i++) {
      serveur
          .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
          .andRespond(
              withSuccess(reponsePageTMJson(1300, true), MediaType.APPLICATION_JSON)
                  .header("Rate-Limit-Available", "9800")
                  .header("Rate-Limit", "10000")
                  .header("Rate-Limit-Reset", "0"));
    }

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchSalle("tm:venue-123");

    assertEquals(1294, source.preleverConcertsManques());
    serveur.verify();
  }

  @Test
  void preleverConcertsManques_aucunePaginationPlafonnee_retourneZero() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "0"));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchSalle("tm:venue-123");

    assertEquals(0, source.preleverConcertsManques());
  }

  @Test
  void redigerApiKey_messageAvecCle_masqueLaCle() {
    final String message =
        "connect timed out:"
            + " https://app.ticketmaster.com/discovery/v2/events.json?apikey=abcd1234&size=200";

    final String resultat = TicketmasterSource.redigerApiKey(message);

    assertFalse(resultat.contains("abcd1234"));
    assertTrue(resultat.contains("apikey=***"));
    assertTrue(resultat.contains("size=200"));
  }

  @Test
  void redigerApiKey_messageSansCle_inchange() {
    final String message = "connect timed out";

    assertEquals(message, TicketmasterSource.redigerApiKey(message));
  }

  @Test
  void redigerApiKey_messageNull_retourneNull() {
    assertNull(TicketmasterSource.redigerApiKey(null));
  }

  // Une panne réseau ne doit jamais être confondue avec une salle sans concert : l'appelant
  // doit pouvoir la distinguer (retry ciblé, aucune absence comptée).
  @Test
  void fetchSalle_panneReseau_propageResourceAccessException() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withException(new java.io.IOException("Connection reset")));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);

    assertThrows(
        org.springframework.web.client.ResourceAccessException.class,
        () -> source.fetchSalle("tm:venue-1"));
  }

  // Un département dense dépasse le plafond de 1000 résultats : la requête reprend par
  // fenêtre de dates plutôt que d'abandonner la suite, et l'horizon couvre toute la fenêtre.
  @Test
  void fetchRegionCouverte_plafondAtteint_reprendParFenetreDeDatesJusquALaFin() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();
    serveur
        .expect(
            org.springframework.test.web.client.ExpectedCount.times(6),
            requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withSuccess(reponsePageTMJson(1400, true), MediaType.APPLICATION_JSON));
    serveur
        .expect(
            requestTo(org.hamcrest.Matchers.containsString("startDateTime=2025-09-14T00:00:00Z")))
        .andRespond(withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON));

    final TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    final ResultatRegion resultat = source.fetchRegionCouverte(List.of("75"), null);

    serveur.verify();
    assertEquals(7, resultat.concerts().size());
    assertEquals(
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(179), resultat.horizon());
  }

  @Test
  void fetchRegionCouverte_departementInconnu_neGarantitAucunHorizon() {
    final TicketmasterSource source =
        new TicketmasterSource("fake-key", RestClient.builder().build());

    assertNull(source.fetchRegionCouverte(List.of("zz"), null).horizon());
  }
}
