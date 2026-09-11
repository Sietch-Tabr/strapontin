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
      final MockRestServiceServer serveur, final RestClient restClient) {
    final Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    return new ResolveurNomCanonique(restClient, cache);
  }

  @Test
  void constructeurSansArgument_initialise_sansErreur() {
    assertDoesNotThrow(() -> new ResolveurNomCanonique());
  }

  @Test
  void resultatResolution_getters_retournentValeursCorrectes() {
    final var resultat = new ResultatResolution("Daft Punk", SourceResolution.DEEZER);
    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
  }

  @Test
  void resoudreNomCanonique_resultatEnCache_retourneSansHttp() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    final var attendu = new ResultatResolution("Daft Punk", SourceResolution.DEEZER);
    cache.put("artiste:daft punk", attendu);

    final ResolveurNomCanonique resolveur = new ResolveurNomCanonique(restClient, cache);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_deezerCorrigeLeNom_retourneDeezer() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Daft Punk"}]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_deezerAucunResultat_retourneInchange() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat =
        resolveur.resoudreNomCanonique("artiste", "Artiste Inconnu");

    assertEquals("Artiste Inconnu", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_erreurDeezer_retourneInchange() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_plusieursCandidats_retientLePlusPopulaire() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

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

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Young Thug");

    assertEquals("Young Thug", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  // Un homonyme approximatif plus populaire ne doit jamais l'emporter sur une correspondance
  // exacte du nom recherché.
  @Test
  void resoudreNomCanonique_artiste_exactMatchMoinsPopulaire_priméSurHomonymePopulaire() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"name":"The Hall Effect","nb_fan":20076},
                  {"name":"The Halo Effect","nb_fan":10837}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat =
        resolveur.resoudreNomCanonique("artiste", "The Halo Effect");

    assertEquals("The Halo Effect", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_candidatPopulaireSousLeSeuil_ignore() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

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

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    // "Ed Sheeran" ne passe pas le seuil de similarité face à "Daft Punk" malgré sa popularité
    // très supérieure — la popularité ne départage que les candidats déjà au-dessus du seuil,
    // elle ne se substitue jamais au filtre de similarité.
    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_popularitAbsenteDuCandidat_traiteeCommeNulle() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Daft Punk"}]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "daft punk");

    assertEquals("Daft Punk", resultat.nom());
    assertEquals(SourceResolution.DEEZER, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_artiste_similariteTropFaible_retourneInchange() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Artiste Completement Different"}]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", "Daft Punk");

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
      final String nomReel, final String homonyme) {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"name\":\"" + homonyme + "\",\"nb_fan\":999999}]}",
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("artiste", nomReel);

    assertEquals(nomReel, resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  @Test
  void resoudreNomCanonique_salle_retourneNomInchangeSansHttp() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    final ResultatResolution resultat = resolveur.resoudreNomCanonique("salle", "Accor Arena");

    assertEquals("Accor Arena", resultat.nom());
    assertEquals(SourceResolution.INCHANGE, resultat.source());
    serveur.verify();
  }

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"name":"Daft Punk"}]}
                """,
                MediaType.APPLICATION_JSON));

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    resolveur.resoudreNomCanonique("artiste", "daft punk");

    final BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttp_compteUneErreur() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    resolveur.resoudreNomCanonique("artiste", "Daft Punk");

    final BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_resultatEnCache_neCompteAucunAppel() {
    final RestClient.Builder builder = RestClient.builder();
    // Aucune expectation configurée : un appel HTTP réel ferait échouer le test (assertion
    // MockRestServiceServer), ce qui est précisément le comportement attendu ici (résultat en
    // cache, aucun appel ne doit être tenté).
    MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final Cache<String, ResultatResolution> cache = Caffeine.newBuilder().build();
    cache.put("artiste:daft punk", new ResultatResolution("Daft Punk", SourceResolution.DEEZER));

    final ResolveurNomCanonique resolveur = new ResolveurNomCanonique(restClient, cache);
    resolveur.resoudreNomCanonique("artiste", "daft punk");

    final BilanFetch bilan = resolveur.preleverBilan();
    assertEquals(0, bilan.appels());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist")))
        .andRespond(withServerError());

    final ResolveurNomCanonique resolveur = resolveurAvecServeur(serveur, restClient);
    resolveur.resoudreNomCanonique("artiste", "Daft Punk");
    resolveur.preleverBilan();

    final BilanFetch second = resolveur.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }
}
