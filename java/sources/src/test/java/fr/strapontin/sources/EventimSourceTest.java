/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

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

  // --- extraireDept (Eventim) ---

  @Test
  void extraireDept_codePostalAbsent_retourneNull() {
    assertNull(EventimSource.extraireDept(null));
    assertNull(EventimSource.extraireDept(""));
    assertNull(EventimSource.extraireDept("   "));
  }

  @Test
  void extraireDept_metropole_retourneDeuxChiffres() {
    assertEquals("75", EventimSource.extraireDept("75012"));
    assertEquals("69", EventimSource.extraireDept("69001"));
    assertEquals("13", EventimSource.extraireDept("13001"));
  }

  @Test
  void extraireDept_DOM_retourneTroisChiffres() {
    assertEquals("972", EventimSource.extraireDept("97200"));
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
    // ticket #620 — La Seine Musicale - Boulogne Billancourt, dept=92, Eventim renvoie "Paris"
    assertEquals(
        "Boulogne Billancourt",
        EventimSource.corrigerVilleEventim(
            "Paris", "92", "La Seine Musicale - Boulogne Billancourt"));
  }

  @Test
  void corrigerVilleEventim_petiteCouronneSansCommuneDansNomSalle_conserveParisEtJournalise() {
    // ticket #620 — Stade de France, dept=93, aucune commune extractible du nom de salle
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
    assertEquals(39.9, resultat.prixMin());
    assertEquals(99.9, resultat.prixMax());
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
    // ticket #620
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

  // --- Tests HTTP ---

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
  void mapProduit_inStockFalse_retourneStatutPresale() {
    Map<String, Object> produit = produitEventimRaw(Map.of("inStock", false));

    ConcertBrut concert = EventimSource.mapProduit(produit, null);

    assertEquals("presale", concert.statut());
  }

  @Test
  void mapProduit_inStockTrue_retourneStatutNull() {
    Map<String, Object> produit = produitEventimRaw(Map.of("inStock", true));

    ConcertBrut concert = EventimSource.mapProduit(produit, null);

    assertNull(concert.statut());
  }
}
