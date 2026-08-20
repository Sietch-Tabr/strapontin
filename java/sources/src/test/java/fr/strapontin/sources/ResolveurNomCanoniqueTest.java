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
import fr.strapontin.sources.ResolveurNomCanonique.ResultatResolution;
import fr.strapontin.sources.ResolveurNomCanonique.SourceResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  void resoudreNomCanonique_artiste_plusieursCandidats_retientLePlusPopulaire() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"name":"Young T.H.U.G.","nb_fan":323},
                  {"name":"Young Thug","nb_fan":1547139}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Young Thug");

    assertEquals("Young Thug", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_candidatPopulaireSousLeSeuil_ignore() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"name":"Ed Sheeran","nb_fan":100000000},
                  {"name":"Daft Punk","nb_fan":5000000}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    // "Ed Sheeran" ne passe pas le seuil de similarité face à "Daft Punk" malgré sa popularité
    // très supérieure — la popularité ne départage que les candidats déjà au-dessus du seuil,
    // elle ne se substitue jamais au filtre de similarité.
    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_popularitAbsenteDuCandidat_traiteeCommeNulle() {
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

  // non-régression : sans le vrai artiste dans le pool Deezer, un homonyme rimé mais mieux
  // référencé ne doit plus franchir le seuil de confiance (corruption réelle constatée en
  // production avant relèvement du seuil 0.7 -> 0.85).
  @ParameterizedTest
  @CsvSource({"Monolord,Jon Lord", "The Brooks,The Kooks", "Sara Correia,Sara Carreira"})
  void resoudreNomCanonique_artiste_homonymeRimeAbsentDuPool_retourneInchange(
      String nomReel, String homonyme) {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"name\":\"" + homonyme + "\",\"nb_fan\":999999}]}",
                MediaType.APPLICATION_JSON));

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", nomReel);

    assertEquals(nomReel, resultat.nom());
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

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
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
    resolveur.resoudreNomCanonique("artiste", "daft punk");

    BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttp_compteUneErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_resultatEnCache_neCompteAucunAppel() {
    RestClient.Builder builder = RestClient.builder();
    // Aucune expectation configurée : un appel HTTP réel ferait échouer le test (assertion
    // MockRestServiceServer), ce qui est précisément le comportement attendu ici (résultat en
    // cache, aucun appel ne doit être tenté).
    MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    cache.put("artiste:daft punk", new ResultatResolution("Daft Punk", SourceResolution.DEEZER));

    ResolveurNomCanonique resolveur = new ResolveurNomCanonique(restClient, cache);
    resolveur.resoudreNomCanonique("artiste", "daft punk");

    BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(0, bilan.appels());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    resolveur.resoudreNomCanonique("artiste", "Daft Punk");
    resolveur.preleverBilan();

    BilanFetch second = resolveur.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }
}
