/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeezerArtisteSourceTest {

  @Test
  void nom_retourneDeezer() {
    assertEquals("deezer", new DeezerArtisteSource().nom());
  }

  // Régression : le P2722 (deezerId) Wikidata est fréquemment absent en pratique (constaté en
  // production sur Yelle, Mademoiselle K, Ycare) alors que Deezer référence bien ces artistes —
  // la découverte par nom ne doit donc pas dépendre uniquement de Wikidata.
  @Test
  void enrichirLot_deezerIdAbsent_decouvreParNom() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Yelle", "yelle", "Q193338", null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist?q=Yelle")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"id":2306,"name":"Yelle"}]}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertEquals(1, resultat.size());
    final EnrichissementArtiste enrichissement = resultat.get(id);
    assertEquals("Yelle", enrichissement.nomAffiche());
    assertEquals("2306", enrichissement.deezerId());
    assertEquals("Q193338", enrichissement.wikidataId());
    serveur.verify();
  }

  // Sellig : humoriste, dont l'homonyme musicien de 56 fans avait reçu le deezer_id.
  @Test
  void enrichirLot_artisteDeSpectacleNonMusical_neDecouvrePasParNom() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir humoriste =
        new ArtisteAEnrichir(id, "Sellig", "sellig", "Q16676017", null, true);

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(builder.build()).enrichirLot(List.of(humoriste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteNomAccentue_encodeLeNomUneSeuleFois() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(UUID.randomUUID(), "Mylène Farmer", "mylene farmer", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("q=Myl%C3%A8ne%20Farmer&")))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

    new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteHomonymesExacts_retientLePlusSuivi() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Flo", "flo", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist?q=Flo")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"id":261428531,"name":"Flö","nb_fan":37},
                {"id":168790157,"name":"FLO","nb_fan":27216}]}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertEquals("168790157", resultat.get(id).deezerId());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteNomDifferent_ignoreCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Jain", "jain", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist?q=Jain")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"id":123,"name":"Anuv Jain"}]}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteAucunResultat_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Artiste Inconnu", "artiste inconnu", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/artist?q=")))
        .andRespond(
            withSuccess(
                """
                {"data":[]}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseValide_retourneNomConfirme() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Daft Punk", "daft punk", "Q167023", "27");

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/artist/27")))
        .andRespond(
            withSuccess(
                """
                {"id":27,"name":"Daft Punk"}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertEquals(1, resultat.size());
    final EnrichissementArtiste enrichissement = resultat.get(id);
    assertEquals("Daft Punk", enrichissement.nomAffiche());
    assertEquals("27", enrichissement.deezerId());
    assertEquals("Q167023", enrichissement.wikidataId());
    serveur.verify();
  }

  @Test
  void enrichirLot_deezerId404_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste =
        new ArtisteAEnrichir(id, "Artiste", "artiste", null, "999999999");

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/artist/999999999")))
        .andRespond(
            withSuccess(
                """
                {"error":{"type":"DataException","message":"no data","code":800}}
                """,
                MediaType.APPLICATION_JSON));

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_erreurReseau_retourneVideSansException() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final ArtisteAEnrichir artiste = new ArtisteAEnrichir(id, "Artiste", "artiste", null, "27");

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("/artist/27")))
        .andRespond(withServerError());

    final Map<UUID, EnrichissementArtiste> resultat =
        new DeezerArtisteSource(restClient).enrichirLot(List.of(artiste));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }
}
