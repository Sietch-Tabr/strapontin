/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.QuotaTM;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TicketmasterSourceTest {

  // --- Fixtures ---

  private static Map<String, Object> evenementTMRaw() {
    return evenementTMRaw(Map.of());
  }

  private static Map<String, Object> evenementTMRaw(Map<String, Object> surcharge) {
    Map<String, Object> base = new HashMap<>();
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

  private static String reponsePageTMJson(int totalElements, boolean avecEvents) {
    String events =
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
    int totalPages = (int) Math.ceil((double) totalElements / 200);
    return """
    {"_embedded":{"events":%s},"page":{"totalElements":%d,"totalPages":%d}}
    """
        .formatted(events, totalElements, totalPages);
  }

  // --- parseDateTM ---

  @Test
  void parseDateTM_dateEtHeureValides_retourneDateEtHeure() {
    String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", "20:30:00");
    assertEquals("2025-09-15", resultat[0]);
    assertEquals("20:30", resultat[1]);
  }

  @Test
  void parseDateTM_heureAbsente_retourneHeureNull() {
    String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", null);
    assertEquals("2025-09-15", resultat[0]);
    assertNull(resultat[1]);
  }

  @Test
  void parseDateTM_heure0000_retourneHeureNull() {
    String[] resultat = TicketmasterSource.parseDateTM("2025-09-15", "00:00:00");
    assertNull(resultat[1]);
  }

  @Test
  void parseDateTM_dateAvecHeure_tronqueA10Caracteres() {
    String[] resultat = TicketmasterSource.parseDateTM("2025-09-15T20:30:00Z", "20:30:00");
    assertEquals("2025-09-15", resultat[0]);
    assertEquals("20:30", resultat[1]);
  }

  // --- mapEvenement ---

  @Test
  void mapEvenement_evenementComplet_parseTousLesChamps() {
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
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
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions", List.of(),
                    "venues",
                        List.of(
                            Map.of(
                                "name", "Zénith Paris",
                                "city", Map.of("name", "Paris"),
                                "postalCode", "75019")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "Julien Clerc");
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  @Test
  void mapEvenement_sansAttractionNiFallback_retourneNull() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "Radiohead - OK Computer Tour",
                "_embedded",
                Map.of(
                    "attractions", List.of(),
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
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
    assertNotNull(resultat);
    assertEquals("Accor Arena", resultat.salle());
  }

  @Test
  void mapEvenement_salleAvecApostrophe_capitaliseApresApostrophe() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions", List.of(Map.of("name", "Julien Clerc")),
                    "venues",
                        List.of(
                            Map.of(
                                "name", "L'OLYMPIA",
                                "city", Map.of("name", "Paris"),
                                "postalCode", "75009")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("L'Olympia", resultat.salle());
  }

  @Test
  void mapEvenement_fauxPositifFallback_rejetteEvenementSansRapport() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "Déjeuner pour 2 personnes Tour Eiffel",
                "_embedded",
                Map.of(
                    "attractions", List.of(),
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
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "name",
                "2 Unlimited - Greatest Hits Tour",
                "_embedded",
                Map.of(
                    "attractions", List.of(),
                    "venues",
                        List.of(
                            Map.of(
                                "name", "Zénith Paris",
                                "city", Map.of("name", "Paris"),
                                "postalCode", "75019")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "2 Unlimited");
    assertNotNull(resultat);
    assertEquals("2 Unlimited", resultat.artiste());
  }

  @Test
  void mapEvenement_dateAbsente_retourneNull() {
    Map<String, Object> evt = new HashMap<>(evenementTMRaw());
    evt.put("dates", Map.of("start", Map.of()));
    assertNull(TicketmasterSource.mapEvenement(evt, null));
  }

  @Test
  void mapEvenement_classificationArtsTheatre_retourneTheatre() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "classifications",
                List.of(
                    Map.of(
                        "segment", Map.of("name", "Arts et théâtre"),
                        "genre", Map.of("name", "Théâtre")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("theatre", resultat.classification());
  }

  @Test
  void mapEvenement_urlAbsente_retourneNullPourUrl() {
    Map<String, Object> evt = new HashMap<>(evenementTMRaw());
    evt.remove("url");
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertNull(resultat.url());
  }

  @Test
  void mapEvenement_DOMGuadeloupe_retourneDept971() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions", List.of(Map.of("name", "Artiste")),
                    "venues",
                        List.of(
                            Map.of(
                                "name", "Salle",
                                "city", Map.of("name", "Pointe-à-Pitre"),
                                "postalCode", "97100")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, null);
    assertNotNull(resultat);
    assertEquals("971", resultat.dept());
  }

  // --- Tests HTTP ---

  @Test
  void fetchArtiste_reponseValide_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Julien Clerc");

    assertEquals(1, resultats.size());
    assertEquals("Julien Clerc", resultats.get(0).artiste());
    serveur.verify();
  }

  @Test
  void fetchArtiste_embeddedAbsent_retourneListeVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

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

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Artiste Inconnu");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void fetchArtiste_metsAJourLeQuota() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(0, false), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Radiohead");

    QuotaTM quota = source.obtenirQuota();
    assertNotNull(quota);
    assertEquals(9800, quota.dispo());
    assertEquals(10000, quota.total());
    serveur.verify();
  }

  @Test
  void fetchArtistesLot_listeVide_retourneListeVide() {
    TicketmasterSource source = new TicketmasterSource("fake-key");
    assertTrue(source.fetchArtistesLot(List.of()).isEmpty());
  }

  @Test
  void fetchArtistesLot_avecIds_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchArtistesLot(List.of("tm-123", "tm-456"));

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void accepteIdentifiantSalle_prefixeTm_retourneVrai() {
    TicketmasterSource source = new TicketmasterSource("fake-key");
    assertTrue(source.accepteIdentifiantSalle("tm:rZFSnyZdFvA"));
    assertFalse(source.accepteIdentifiantSalle("digitick:lempreinte"));
    assertFalse(source.accepteIdentifiantSalle("rZFSnyZdFvA"));
    assertFalse(source.accepteIdentifiantSalle(null));
  }

  @Test
  void fetchSalle_identifiantPrefixe_stripPrefixeEtRetourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchSalle("tm:venue-123");

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void fetchSalle_identifiantBrut_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchSalle("venue-123");

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void fetchSalle_aucunEvent_retourneListeVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(0, false), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchSalle("tm:venue-vide");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  // Un échec HTTP (contrairement à une salle réellement sans concert, testé ci-dessus) doit
  // rester distinguable après coup — voir ResultatPage#succes, propagé jusqu'au log de fetchSalle.
  @Test
  void fetchSalle_erreurHttp_retourneListeVideSansLeverDException() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchSalle("tm:venue-en-echec");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_nomTrouve_retourneId() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"attractions":[{"id":"tm-456","name":"Julien Clerc"}]}}
                """,
                MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    String id = source.rechercherAttractionId("Julien Clerc");

    assertEquals("tm-456", id);
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_aucuneCorrespondanceExacteParmis10_retourneNull() {
    // non-régression : TM retourne "Anuv Jain" sans "Jain" parmi 10 résultats → null
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

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

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    String id = source.rechercherAttractionId("Jain");

    assertNull(id);
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_correspondanceExacteApresDecalage_retourneId() {
    // non-régression : TM retourne "Shy" avant "Shy'm" (tri popularité) → doit trouver Shy'm
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

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

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    String id = source.rechercherAttractionId("Shy'm");

    assertEquals("K8vZ917shym", id);
    serveur.verify();
  }

  @Test
  void rechercherAttractionId_nomNonTrouve_retourneNull() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    String id = source.rechercherAttractionId("Artiste Inconnu");

    assertNull(id);
    serveur.verify();
  }

  @Test
  void fetchRegion_deptInconnu_retourneListeVide() {
    TicketmasterSource source = new TicketmasterSource("fake-key");
    List<ConcertBrut> resultats = source.fetchRegion(List.of("ZZ"), null);
    assertTrue(resultats.isEmpty());
  }

  @Test
  void fetchRegion_deptConnu_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "1726420000000"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<ConcertBrut> resultats = source.fetchRegion(List.of("75"), null);

    assertEquals(1, resultats.size());
    serveur.verify();
  }

  @Test
  void mapEvenement_offsaleAvecVentePubliqueFuture_retourneStatutPresale() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                    Map.of(
                        "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                        "status", Map.of("code", "offsale")),
                "sales", Map.of("public", Map.of("startDateTime", "2099-01-01T12:00:00Z"))));

    ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("presale", concert.statut());
    assertEquals("2099-01-01T12:00:00Z", concert.presaleDebut());
  }

  @Test
  void rechercherAttractionId_erreurHttp_retourneNull() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions.json")))
        .andRespond(withServerError());

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    String id = source.rechercherAttractionId("Artiste");

    assertNull(id);
    serveur.verify();
  }

  @Test
  void mapEvenement_offsaleAvecVentePubliquePassee_resteOffsale() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "dates",
                    Map.of(
                        "start", Map.of("localDate", "2026-11-03", "localTime", "20:00:00"),
                        "status", Map.of("code", "offsale")),
                "sales", Map.of("public", Map.of("startDateTime", "2020-01-01T12:00:00Z"))));

    ConcertBrut concert = TicketmasterSource.mapEvenement(evt, null);

    assertEquals("offsale", concert.statut());
  }

  // --- filtrage homonymes / partiels ---

  @Test
  void mapEvenement_fallbackEtAttractionNomDifferent_retourneNull() {
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions", List.of(Map.of("name", "Anuv Jain")),
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
    Map<String, Object> evt =
        evenementTMRaw(
            Map.of(
                "_embedded",
                Map.of(
                    "attractions", List.of(Map.of("name", "Julien Clerc")),
                    "venues",
                        List.of(
                            Map.of(
                                "name", "Zénith Paris",
                                "city", Map.of("name", "Paris"),
                                "postalCode", "75019")))));
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evt, "Julien Clerc");
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  @Test
  void mapEvenement_fallbackNullAttractionPresente_accepteEvenementSansVerification() {
    // fetchSalle passe null comme fallback — pas de filtrage par nom attendu
    ConcertBrut resultat = TicketmasterSource.mapEvenement(evenementTMRaw(), null);
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
  }

  // --- Tests santé des sources ---

  @Test
  void verifierVenue_reponse200_retourneVivant() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues/rZFSnyZdFvA.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.VIVANT, source.verifierVenue("rZFSnyZdFvA"));
    serveur.verify();
  }

  @Test
  void verifierVenue_reponse404_retourneMort() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues/venueMort.json")))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.MORT, source.verifierVenue("venueMort"));
    serveur.verify();
  }

  @Test
  void verifierAttraction_reponse404_retourneMort() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(
            requestTo(org.hamcrest.Matchers.containsString("/attractions/attractionMorte.json")))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(TicketmasterSource.StatutIdTM.MORT, source.verifierAttraction("attractionMorte"));
    serveur.verify();
  }

  @Test
  void rechercherVenuesParNom_reponseValide_retourneCandidats() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues.json")))
        .andRespond(
            withSuccess(
                """
                {"_embedded":{"venues":[{"id":"rZ7abc","name":"Zenith Nantes"}]}}
                """,
                MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    List<TicketmasterSource.CandidatVenueTM> candidats =
        source.rechercherVenuesParNom("Zenith Nantes");

    assertEquals(1, candidats.size());
    assertEquals("rZ7abc", candidats.get(0).id());
    serveur.verify();
  }

  @Test
  void rechercherVenuesParNom_embeddedAbsent_retourneListeVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/venues.json")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertTrue(source.rechercherVenuesParNom("Inconnue").isEmpty());
    serveur.verify();
  }

  @Test
  void compterEventsVenue_reponseValide_retourneTotal() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(
                """
                {"page":{"totalElements":3}}
                """,
                MediaType.APPLICATION_JSON));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(3, source.compterEventsVenue("rZ7abc"));
    serveur.verify();
  }

  @Test
  void compterEventsVenue_erreurReseau_retourneZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    assertEquals(0, source.compterEventsVenue("rZ7abc"));
    serveur.verify();
  }

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "0"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttp_compteUneErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(withServerError());

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchArtiste("Julien Clerc");
    source.preleverBilan();

    BilanFetch second = source.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }

  // concertsManques reste une métrique TM spécifique, distincte du bilan appels/erreurs commun
  // : pagination plafonnée à NB_PAGES_MAX (6) pages de 200 événements, totalElements=1300
  // déclenche un plafonnement (7 pages nécessaires) laissant 1300 - 6 = 1294 concerts non
  // récupérés (6 pages fetchées, 1 événement par page dans la fixture).
  @Test
  void preleverConcertsManques_paginationPlafonnee_compteLesConcertsNonRecuperes() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    for (int i = 0; i < 6; i++) {
      serveur
          .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
          .andRespond(
              withSuccess(reponsePageTMJson(1300, true), MediaType.APPLICATION_JSON)
                  .header("Rate-Limit-Available", "9800")
                  .header("Rate-Limit", "10000")
                  .header("Rate-Limit-Reset", "0"));
    }

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchSalle("tm:venue-123");

    assertEquals(1294, source.preleverConcertsManques());
    serveur.verify();
  }

  @Test
  void preleverConcertsManques_aucunePaginationPlafonnee_retourneZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/events.json")))
        .andRespond(
            withSuccess(reponsePageTMJson(1, true), MediaType.APPLICATION_JSON)
                .header("Rate-Limit-Available", "9800")
                .header("Rate-Limit", "10000")
                .header("Rate-Limit-Reset", "0"));

    TicketmasterSource source = new TicketmasterSource("fake-key", restClient);
    source.fetchSalle("tm:venue-123");

    assertEquals(0, source.preleverConcertsManques());
  }
}
