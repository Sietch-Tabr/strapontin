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
import fr.strapontin.sources.ResolveurNomCanonique.ResultatResolution;
import fr.strapontin.sources.ResolveurNomCanonique.SourceResolution;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResolveurNomCanoniqueTest {

  private static ResolveurNomCanonique resolveurAvecServeur(
      MockRestServiceServer serveur, RestClient restClient) {
    Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    return new ResolveurNomCanonique(restClient, cache);
  }

  @Test
  void constructeurSansArgument_initialise_sansErreur() {
    assertDoesNotThrow(() -> new ResolveurNomCanonique());
  }

  @Test
  void resultatResolution_getters_retournentValeursCorrectes() {
    var resultat = new ResultatResolution("Daft Punk", SourceResolution.DEEZER);
    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
  }

  @Test
  void resoudreNomCanonique_resultatEnCache_retourneSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    var attendu = new ResultatResolution("Daft Punk", SourceResolution.DEEZER);
    cache.put("artiste:daft punk", attendu);

    ResolveurNomCanonique resolveur = new ResolveurNomCanonique(restClient, cache);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_deezerCorrigeLeNom_retourneDeezer() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Daft Punk"}]}
                """,
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_deezerAucunResultat_retourneInchange() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[]}
                """,
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Artiste Inconnu");

    assertEquals("Artiste Inconnu", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_erreurDeezer_retourneInchange() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_similariteTropFaible_retourneInchange() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Artiste Completement Different"}]}
                """,
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_salle_retourneNomInchangeSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("salle", "Accor Arena");

    assertEquals("Accor Arena", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }
}
