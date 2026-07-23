/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WikidataArtisteSourceTest {

  private static WikidataArtisteSource source(RestClient restClient) {
    return new WikidataArtisteSource(restClient, Caffeine.newBuilder().build());
  }

  @Test
  void nom_retourneWikidata() {
    assertEquals("wikidata", new WikidataArtisteSource().nom());
  }

  @Test
  void enrichirLot_wikidataIdConnu_requeteBatchEtRetourneP2722EtP6262() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Daft Punk", "daft punk", "Q167023", null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q167023"},
                   "itemLabel":{"type":"literal","value":"Daft Punk"},
                   "deezerId":{"type":"literal","value":"27"},
                   "tmId":{"type":"literal","value":"K8vZ9171Xf0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertEquals(1, resultat.size());
    EnrichissementArtiste enrichissement = resultat.get(id);
    assertEquals("Q167023", enrichissement.wikidataId());
    assertEquals("Daft Punk", enrichissement.nomAffiche());
    assertEquals("27", enrichissement.deezerId());
    assertEquals("K8vZ9171Xf0", enrichissement.ticketmasterAttractionId());
    serveur.verify();
  }

  @Test
  void enrichirLot_wikidataIdInconnu_utiliseDecouverteMwapi() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Angele", "angele", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("wikibase%3Amwapi")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q243550"},
                   "itemLabel":{"type":"literal","value":"Angèle"},
                   "num":{"type":"literal","value":"1"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertEquals(1, resultat.size());
    assertEquals("Q243550", resultat.get(id).wikidataId());
    assertEquals("Angèle", resultat.get(id).nomAffiche());
    serveur.verify();
  }

  // Régression : Muse (P31="groupe de rock", Q5741069) est une sous-classe de "groupe musical"
  // (Q215380), pas une instance directe — un filtre de type strict (?item wdt:P31 wd:Q215380)
  // élimine silencieusement la quasi-totalité des groupes réels. Constaté en production (dev,
  // Muse et Feu! Chatterton sans deezerId) avant correction vers la fermeture transitive
  // wdt:P31/wdt:P279*.
  @Test
  void enrichirLot_decouverte_utiliseFiltreTypeTransitifPourLesGroupes() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Muse", "muse", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("P279")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q22151"},
                   "itemLabel":{"type":"literal","value":"Muse"},
                   "deezerId":{"type":"literal","value":"705"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertEquals("Q22151", resultat.get(id).wikidataId());
    assertEquals("705", resultat.get(id).deezerId());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteAucunCandidat_retourneVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Artiste Inconnu", "artiste inconnu", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // #767 : un humoriste (P106 humoriste, pas musicien) doit être rapprochable — avant correction,
  // le filtre de découverte n'acceptait que "humain+musicien" ou "groupe", excluant
  // structurellement des artistes légitimement suivis comme Pierre-Emmanuel Barré.
  @Test
  void enrichirLot_decouverteHumoriste_estRapproche() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Pierre-Emmanuel Barré", "pierre-emmanuel barre", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q245068")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3382524"},
                   "itemLabel":{"type":"literal","value":"Pierre-Emmanuel Barré"},
                   "typeMatch":{"type":"literal","value":"humain"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertEquals("Q3382524", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // #1018 : un auteur comique (P106 = Q12406482, ex. Pierre-Emmanuel Barré dont la fiche réelle
  // porte cette occupation plutôt que Q245068) doit aussi être rapprochable — la fermeture
  // transitive P279* de Q12406482 ne remonte jamais vers Q245068/Q639669, un troisième
  // branchement du filtre était nécessaire.
  @Test
  void enrichirLot_decouverteAuteurComique_estRapproche() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Auteur Comique Inconnu", "auteur comique inconnu", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q12406482")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q17350457"},
                   "itemLabel":{"type":"literal","value":"Auteur Comique Inconnu"},
                   "typeMatch":{"type":"literal","value":"humain"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertEquals("Q17350457", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // #772 : homonymie groupe/personne (ex. Van Halen, groupe nommé du vrai nom de famille de son
  // fondateur) — deux entités distinctes de types différents parmi les meilleurs candidats
  // doivent bloquer tout rapprochement plutôt que de deviner celle en tête.
  @Test
  void enrichirLot_decouverteAmbigueGroupeEtPersonne_neRapprocheRien() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Van Halen", "van halen", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q483472"},
                   "itemLabel":{"type":"literal","value":"Van Halen"},
                   "typeMatch":{"type":"literal","value":"groupe"},
                   "num":{"type":"literal","value":"0"}},
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q222832"},
                   "itemLabel":{"type":"literal","value":"Eddie Van Halen"},
                   "typeMatch":{"type":"literal","value":"humain"},
                   "num":{"type":"literal","value":"1"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_lotVide_retourneVideSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    Map<UUID, EnrichissementArtiste> resultat = source(restClient).enrichirLot(List.of());

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }
}
