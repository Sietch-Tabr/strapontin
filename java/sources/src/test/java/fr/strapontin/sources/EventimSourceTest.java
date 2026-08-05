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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EventimSourceTest {

  // --- Fixtures ---

  private static Map<String, Object> produitEventimRaw() {
    return produitEventimRaw(Map.of());
  }

  private static Map<String, Object> produitEventimRaw(Map<String, Object> surcharge) {
    Map<String, Object> base = new HashMap<>();
    base.put("name", "Julien Clerc");
    base.put(
        "typeAttributes",
        Map.of(
            "liveEntertainment",
            Map.of(
                "startDate", "2025-09-15T20:30:00",
                "location",
                    Map.of(
                        "name", "Accor Arena",
                        "city", "Paris",
                        "postalCode", "75012"),
                "priceRange", Map.of("min", 39.9, "max", 99.9))));
    base.put("link", "https://www.eventim.fr/event/julien-clerc-1234");
    base.putAll(surcharge);
    return base;
  }

  // --- corrigerVilleEventim ---

  @Test
  void corrigerVilleEventim_deptParis_neCorrigePas() {
    assertEquals("Paris", EventimSource.corrigerVilleEventim("Paris", "75", "Accor Arena"));
  }

  @Test
  void corrigerVilleEventim_villeAutreQueParis_neCorrigePas() {
    assertEquals(
        "Boulogne-Billancourt",
        EventimSource.corrigerVilleEventim("Boulogne-Billancourt", "92", "La Seine Musicale"));
  }

  @Test
  void corrigerVilleEventim_petiteCouronneAvecCommuneDansNomSalle_extraitCommune() {
    // ticket — La Seine Musicale - Boulogne Billancourt, dept=92, Eventim renvoie "Paris"
    assertEquals(
        "Boulogne Billancourt",
        EventimSource.corrigerVilleEventim(
            "Paris", "92", "La Seine Musicale - Boulogne Billancourt"));
  }

  @Test
  void corrigerVilleEventim_petiteCouronneSansCommuneDansNomSalle_conserveParisEtJournalise() {
    // ticket — Stade de France, dept=93, aucune commune extractible du nom de salle
    assertEquals("Paris", EventimSource.corrigerVilleEventim("Paris", "93", "Stade De France"));
  }

  @Test
  void corrigerVilleEventim_villeOuDeptAbsent_retourneVilleInchangee() {
    assertNull(EventimSource.corrigerVilleEventim(null, "92", "La Seine Musicale"));
    assertEquals("Paris", EventimSource.corrigerVilleEventim("Paris", null, "Accor Arena"));
  }

  // --- parseDateEventim ---

  @Test
  void parseDateEventim_dateEtHeureValides_parseDateEtHeure() {
    String[] r = EventimSource.parseDateEventim("2025-09-15T20:30:00");
    assertEquals("2025-09-15", r[0]);
    assertEquals("20:30", r[1]);
  }

  @Test
  void parseDateEventim_heure0000_retourneHeureNull() {
    String[] r = EventimSource.parseDateEventim("2025-09-15T00:00:00");
    assertEquals("2025-09-15", r[0]);
    assertNull(r[1]);
  }

  @Test
  void parseDateEventim_sansSeparateurT_retourneHeureNull() {
    String[] r = EventimSource.parseDateEventim("2025-09-15");
    assertEquals("2025-09-15", r[0]);
    assertNull(r[1]);
  }

  @Test
  void parseDateEventim_extraitHHMMSeulement() {
    String[] r = EventimSource.parseDateEventim("2025-09-15T21:45:30");
    assertEquals("21:45", r[1]);
  }

  // --- extraireNomArtiste ---

  @Test
  void extraireNomArtiste_avecSeparateurTiret_retournePartieDroite() {
    assertEquals("Coldplay", EventimSource.extraireNomArtiste("Coldplay - Music Of The Spheres"));
  }

  @Test
  void extraireNomArtiste_avecSeparateurDeuxPoints_retournePartieDroite() {
    assertEquals("Artiste", EventimSource.extraireNomArtiste("Artiste : Spectacle 2025"));
  }

  @Test
  void extraireNomArtiste_sansSeparateur_retourneTitreComblet() {
    assertEquals("Julien Clerc", EventimSource.extraireNomArtiste("Julien Clerc"));
  }

  // --- mapProduit ---

  @Test
  void mapProduit_produitComplet_parseTousLesChamps() {
    ConcertBrut resultat = EventimSource.mapProduit(produitEventimRaw(), null);
    assertNotNull(resultat);
    assertEquals("Julien Clerc", resultat.artiste());
    assertEquals("2025-09-15", resultat.date());
    assertEquals("20:30", resultat.heure());
    assertEquals("Accor Arena", resultat.salle());
    assertEquals("Paris", resultat.ville());
    assertEquals("75", resultat.dept());
    assertEquals("https://www.eventim.fr/event/julien-clerc-1234", resultat.url());
    assertEquals("eventim", resultat.source());
    assertNull(resultat.classification());
    assertNull(resultat.genre());
    assertNull(resultat.statut());
  }

  @Test
  void mapProduit_dateAbsente_retourneNull() {
    Map<String, Object> produit =
        produitEventimRaw(Map.of("typeAttributes", Map.of("liveEntertainment", Map.of())));
    assertNull(EventimSource.mapProduit(produit, null));
  }

  @Test
  void mapProduit_nomAbsent_retourneNull() {
    Map<String, Object> produit = new HashMap<>(produitEventimRaw());
    produit.remove("name");
    assertNull(EventimSource.mapProduit(produit, null));
  }

  @Test
  void mapProduit_urlDepuisDomainEtPath_reconstruitUrl() {
    Map<String, Object> produit =
        produitEventimRaw(
            Map.of(
                "url",
                Map.of("domain", "https://www.eventim.fr", "path", "/event/julien-clerc-1234")));
    Map<String, Object> base = new HashMap<>(produit);
    base.remove("link");
    ConcertBrut resultat = EventimSource.mapProduit(base, null);
    assertNotNull(resultat);
    assertEquals("https://www.eventim.fr/event/julien-clerc-1234", resultat.url());
  }

  @Test
  void mapProduit_sansUrlNiLien_retourneUrlNull() {
    Map<String, Object> produit = new HashMap<>(produitEventimRaw());
    produit.remove("link");
    produit.remove("url");
    ConcertBrut resultat = EventimSource.mapProduit(produit, null);
    assertNotNull(resultat);
    assertNull(resultat.url());
  }

  @Test
  void mapProduit_salleSeineMusicaleVillePariseIncoherente_corrigeVilleEnBoulogne() {
    // ticket
    Map<String, Object> produit =
        produitEventimRaw(
            Map.of(
                "typeAttributes",
                Map.of(
                    "liveEntertainment",
                    Map.of(
                        "startDate",
                        "2025-09-15T20:30:00",
                        "location",
                        Map.of(
                            "name", "La Seine Musicale - Boulogne Billancourt",
                            "city", "Paris",
                            "postalCode", "92100")))));
    ConcertBrut resultat = EventimSource.mapProduit(produit, null);
    assertNotNull(resultat);
    assertEquals("Boulogne Billancourt", resultat.ville());
    assertEquals("92", resultat.dept());
  }

  @Test
  void mapProduit_locationAbsente_salleInconnueEtDeptNull() {
    Map<String, Object> produit =
        produitEventimRaw(
            Map.of(
                "typeAttributes",
                Map.of("liveEntertainment", Map.of("startDate", "2025-09-15T20:30:00"))));
    ConcertBrut resultat = EventimSource.mapProduit(produit, null);
    assertNotNull(resultat);
    assertEquals("Salle inconnue", resultat.salle());
    assertNull(resultat.dept());
  }

  // timeout HTTP explicite (connexion 10s/lecture 30s) via ClientHttpFactory
  @Test
  void constructeurParDefaut_neLancePasException_etExposeLeNomEventim() {
    EventimSource source = new EventimSource();

    assertEquals("eventim", source.nom());
  }

  // régression : un User-Agent de navigateur (Mozilla/5.0...) déclenche un blocage 403
  // Akamai depuis le 09/07/2026 ; un Accept-Encoding explicite fait échouer la désérialisation
  // JSON (SimpleClientHttpRequestFactory ne décompresse pas la réponse gzip lui-même).
  @Test
  void builderAvecHeadersParDefaut_headersReels_userAgentHonneteEtAucunAcceptEncoding() {
    RestClient.Builder builder = EventimSource.builderAvecHeadersParDefaut();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andExpect(header("User-Agent", EventimSource.USER_AGENT))
        .andExpect(headerDoesNotExist("Accept-Encoding"))
        .andRespond(
            withSuccess(
                """
                {"attractions":[]}
                """,
                MediaType.APPLICATION_JSON));

    new EventimSource(restClient).fetchArtiste("Test");

    serveur.verify();
    assertFalse(EventimSource.USER_AGENT.startsWith("Mozilla"));
  }

  // --- Tests HTTP ---

  @Test
  void fetchArtiste_nomSansCorrespondanceExacte_retourneListeVide() {
    // ticket — rechercherAttractionId ne doit pas prendre attractions.get(0) si pas d'exact
    // match normalisé (ex: Shy'm → API retourne "Shy", normalisé différent)
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-808","name":"Shy"}]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Shy'm");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void fetchArtiste_termeContenuDansNomPlusVerbeux_retourneResultatsParContainment() {
    // ticket — "-M-" contenu dans "-M- Matthieu Chedid" → accepté par containment
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-m","name":"-M- Matthieu Chedid"}]}
                """,
                MediaType.APPLICATION_JSON));

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("evt-m")))
        .andRespond(
            withSuccess(
                """
                {"products":[],"totalResults":0}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("-M-");

    // Liste vide car products vide, mais l'ID a bien été utilisé (verify passe)
    assertTrue(resultats.isEmpty());
    serveur.verify();

    // le containment accepté doit être exposé pour remontée dans logs_admin
    List<String> containments = source.preleverContainmentsAcceptes();
    assertEquals(1, containments.size());
    assertTrue(containments.get(0).contains("-M-"));
    assertTrue(containments.get(0).contains("-M- Matthieu Chedid"));
    assertTrue(source.preleverContainmentsAcceptes().isEmpty());
  }

  // régression : "Furies" trouvé par containment dans "Love & Furies" (deux
  // entités distinctes) réapparaissait à chaque cycle de fetch. Dès que l'artiste a déjà un
  // deezerId ou un wikidataId connu (identité confirmée indépendamment), le repli par
  // containment doit être désactivé — seule une correspondance exacte est acceptée.
  @Test
  void fetchArtiste_identiteConfirmeeParDeezerId_rejetteLeContainment() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-love-furies","name":"Love & Furies"}]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Furies", "656219", null);

    assertTrue(resultats.isEmpty());
    serveur.verify();
    assertTrue(source.preleverContainmentsAcceptes().isEmpty());
  }

  // Symétrique côté wikidataId : même comportement attendu qu'avec un deezerId connu.
  @Test
  void fetchArtiste_identiteConfirmeeParWikidataId_rejetteLeContainment() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-love-furies","name":"Love & Furies"}]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Furies", null, "Q3106253");

    assertTrue(resultats.isEmpty());
    serveur.verify();
    assertTrue(source.preleverContainmentsAcceptes().isEmpty());
  }

  // Identité non confirmée (ni deezerId ni wikidataId) : comportement historique inchangé, le
  // containment reste autorisé — non-régression du cas "-M-" ci-dessus via la nouvelle signature.
  @Test
  void fetchArtiste_identiteNonConfirmee_autoriseLeContainment() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-m","name":"-M- Matthieu Chedid"}]}
                """,
                MediaType.APPLICATION_JSON));

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("evt-m")))
        .andRespond(
            withSuccess(
                """
                {"products":[],"totalResults":0}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("-M-", null, null);

    assertTrue(resultats.isEmpty());
    serveur.verify();
    assertEquals(1, source.preleverContainmentsAcceptes().size());
  }

  @Test
  void fetchArtiste_termeUnCaractereBrut_pasDeContainment() {
    // ticket — terme d'un seul caractère brut (ex. "M") ne déclenche pas le containment
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-x","name":"X Factor"}]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("X");

    assertTrue(resultats.isEmpty());
    serveur.verify();
    assertTrue(source.preleverContainmentsAcceptes().isEmpty());
  }

  @Test
  void fetchArtiste_artisteNonTrouveEventim_retourneListeVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Artiste Inconnu");

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void fetchArtiste_correspondanceExactePreferee_utiliseExactId() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[
                  {"attractionId":"evt-999","name":"Julien Clerc Tribute"},
                  {"attractionId":"evt-123","name":"Julien Clerc"}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("evt-123")))
        .andRespond(
            withSuccess(
                """
                {"products":[],"totalResults":0}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    source.fetchArtiste("Julien Clerc");

    serveur.verify();
  }

  @Test
  void constructeurParDefaut_initialise_sansErreur() {
    assertDoesNotThrow((org.junit.jupiter.api.function.Executable) EventimSource::new);
  }

  @Test
  void fetchRegion_listeDeptVide_retourneListeVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchRegion(List.of(), List.of("music"));

    assertTrue(resultats.isEmpty());
    serveur.verify();
  }

  @Test
  void fetchRegion_utiliseCategoriesEventimIndependammentDesClassifsPassees() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(
            requestTo(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("/products"),
                    org.hamcrest.Matchers.containsString("Concerts%20%26%20Festivals"),
                    org.hamcrest.Matchers.containsString("Classique%20%26%20Danse"))))
        .andRespond(
            withSuccess(
                """
                {"products":[],"totalResults":0}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    // Qu'on passe null, "music" ou les classifs TM, Eventim doit toujours utiliser ses propres
    // catégories
    source.fetchRegion(List.of("75"), null);

    serveur.verify();
  }

  @Test
  void fetchRegion_unDept_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/products")))
        .andRespond(
            withSuccess(
                """
                {"products":[
                  {"name":"Julien Clerc",
                   "typeAttributes":{"liveEntertainment":{
                     "startDate":"2025-09-15T20:30:00",
                     "location":{"name":"Accor Arena","city":"Paris","postalCode":"75012"},
                     "priceRange":{"min":39.9,"max":99.9}}},
                   "link":"https://www.eventim.fr/event/julien-clerc-1234"}
                ],"totalResults":1}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchRegion(List.of("75"), List.of("music"));

    assertEquals(1, resultats.size());
    assertEquals("75", resultats.get(0).dept());
    serveur.verify();
  }

  @Test
  void fetchArtiste_reponseValide_retourneConcerts() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-123","name":"Julien Clerc"}]}
                """,
                MediaType.APPLICATION_JSON));

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/products")))
        .andRespond(
            withSuccess(
                """
                {"products":[
                  {"name":"Julien Clerc",
                   "typeAttributes":{"liveEntertainment":{
                     "startDate":"2025-09-15T20:30:00",
                     "location":{"name":"Accor Arena","city":"Paris","postalCode":"75012"},
                     "priceRange":{"min":39.9,"max":99.9}}},
                   "link":"https://www.eventim.fr/event/julien-clerc-1234"}
                ],"totalResults":1}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    List<ConcertBrut> resultats = source.fetchArtiste("Julien Clerc");

    assertEquals(1, resultats.size());
    assertEquals("Julien Clerc", resultats.get(0).artiste());
    serveur.verify();
  }

  @Test
  void fetchRegion_requete_inclutParamInStock() {
    // le fetch région doit inclure in_stock=true pour exclure les sold out
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(
            requestTo(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("/products"),
                    org.hamcrest.Matchers.containsString("in_stock=true"))))
        .andRespond(
            withSuccess(
                """
                {"products":[],"totalResults":0}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    source.fetchRegion(List.of("75"), List.of("music"));

    serveur.verify();
  }

  @Test
  void mapProduit_inStockFalse_retourneStatutNull() {
    Map<String, Object> produit = produitEventimRaw(Map.of("inStock", false));

    ConcertBrut concert = EventimSource.mapProduit(produit, null);

    assertNull(concert.statut());
  }

  @Test
  void mapProduit_inStockTrue_retourneStatutNull() {
    Map<String, Object> produit = produitEventimRaw(Map.of("inStock", true));

    ConcertBrut concert = EventimSource.mapProduit(produit, null);

    assertNull(concert.statut());
  }

  // --- salleSourceId interne ---

  @Test
  void mapProduit_salleConnue_salleSourceIdGenere() {
    ConcertBrut resultat = EventimSource.mapProduit(produitEventimRaw(), null);

    assertNotNull(resultat.salleSourceId());
    assertTrue(
        resultat.salleSourceId().startsWith("eventim:internal:"),
        "salleSourceId doit commencer par 'eventim:internal:' mais était : "
            + resultat.salleSourceId());
    assertEquals("eventim:internal:accor arena", resultat.salleSourceId());
  }

  @Test
  void mapProduit_locationAbsente_salleSourceIdNull() {
    Map<String, Object> produit =
        produitEventimRaw(
            Map.of(
                "typeAttributes",
                Map.of("liveEntertainment", Map.of("startDate", "2025-09-15T20:30:00"))));

    ConcertBrut resultat = EventimSource.mapProduit(produit, null);

    assertNull(
        resultat.salleSourceId(), "salleSourceId doit être null quand la salle est inconnue");
  }

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[]}
                """,
                MediaType.APPLICATION_JSON));

    EventimSource source = new EventimSource(restClient);
    source.fetchArtiste("Artiste Inconnu");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttpSurAttractions_compteUneErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(withServerError());

    EventimSource source = new EventimSource(restClient);
    source.fetchArtiste("Julien Clerc");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttpSurProducts_compteAppelEtErreurSepares() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(
            withSuccess(
                """
                {"attractions":[{"attractionId":"evt-1","name":"Julien Clerc"}]}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/products")))
        .andRespond(withServerError());

    EventimSource source = new EventimSource(restClient);
    source.fetchArtiste("Julien Clerc");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(2, bilan.appels(), "un appel /attractions + un appel /products");
    assertEquals(1, bilan.erreursHttp(), "seul l'appel /products a échoué");
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/attractions")))
        .andRespond(withServerError());

    EventimSource source = new EventimSource(restClient);
    source.fetchArtiste("Julien Clerc");
    source.preleverBilan();

    BilanFetch second = source.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }
}
