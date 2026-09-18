/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests unitaires de {@link ClientSparqlWikidata#echapperLitteral} — échappement des littéraux
 * insérés dans une requête SPARQL construite par concaténation — et de {@link
 * ClientSparqlWikidata#executer} — retry sur erreurs transitoires (502, content-type inattendu).
 */
class ClientSparqlWikidataTest {

  @Test
  void echapperLitteral_chaineSansCaractereSpecial_retourneInchangee() {
    assertEquals("Radiohead", ClientSparqlWikidata.echapperLitteral("Radiohead"));
  }

  @Test
  void echapperLitteral_guillemet_echappe() {
    assertEquals("Le \\\"Vrai\\\" Nom", ClientSparqlWikidata.echapperLitteral("Le \"Vrai\" Nom"));
  }

  @Test
  void echapperLitteral_antislash_echappe() {
    assertEquals("a\\\\b", ClientSparqlWikidata.echapperLitteral("a\\b"));
  }

  @Test
  void echapperLitteral_antislashAvantGuillemet_ordreCorrect() {
    // L'antislash doit être échappé avant le guillemet, sinon le \" produit par l'échappement
    // du guillemet serait lui-même ré-échappé.
    assertEquals("a\\\\\\\"b", ClientSparqlWikidata.echapperLitteral("a\\\"b"));
  }

  @Test
  void echapperLitteral_sautDeLigne_echappe() {
    assertEquals("ligne1\\nligne2", ClientSparqlWikidata.echapperLitteral("ligne1\nligne2"));
  }

  @Test
  void echapperLitteral_retourChariot_echappe() {
    assertEquals("a\\rb", ClientSparqlWikidata.echapperLitteral("a\rb"));
  }

  // nginx en amont de l'endpoint SPARQL renvoie parfois un 502 transitoire, constaté en
  // production — un simple retry le résout la plupart du temps.
  @Test
  void executer_502BadGateway_retentePuisReussit() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(ClientSparqlWikidata.BASE_URL))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    serveur
        .expect(requestTo(ClientSparqlWikidata.BASE_URL))
        .andRespond(
            withSuccess(
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}",
                MediaType.parseMediaType("application/sparql-results+json")));

    final ClientSparqlWikidata client = new ClientSparqlWikidata(restClient);
    final var resultat = client.executer("SELECT * WHERE {}");

    assertNotNull(resultat);
    serveur.verify();
  }

  // constaté en production : une réponse content-type application/octet-stream au lieu de
  // application/sparql-results+json (proxy intermédiaire) fait échouer la désérialisation
  // Jackson sans que la requête SPARQL elle-même soit en cause — un retry la résout.
  @Test
  void executer_contentTypeInattendu_retentePuisReussit() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(ClientSparqlWikidata.BASE_URL))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body("{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}"));
    serveur
        .expect(requestTo(ClientSparqlWikidata.BASE_URL))
        .andRespond(
            withSuccess(
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}",
                MediaType.parseMediaType("application/sparql-results+json")));

    final ClientSparqlWikidata client = new ClientSparqlWikidata(restClient);
    final var resultat = client.executer("SELECT * WHERE {}");

    assertNotNull(resultat);
    serveur.verify();
  }

  @Test
  void executer_502Persistant_abandonneApresRetriesEtRetourneMapVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    for (int i = 0; i < 3; i++) {
      serveur
          .expect(requestTo(ClientSparqlWikidata.BASE_URL))
          .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    }

    final ClientSparqlWikidata client = new ClientSparqlWikidata(restClient);
    final var resultat = client.executer("SELECT * WHERE {}");

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }
}
