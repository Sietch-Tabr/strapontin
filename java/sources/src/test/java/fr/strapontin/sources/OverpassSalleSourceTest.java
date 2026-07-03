/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

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

class OverpassSalleSourceTest {

  @Test
  void nom_retourneOverpass() {
    assertEquals("overpass", new OverpassSalleSource().nom());
  }

  @Test
  void enrichirLot_salleAvecWikidataIdConnu_ignoree() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q123");

    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseValideAvecWikidata_retourneEnrichissement() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Zenith de Nantes",
            fr.strapontin.core.Normalisation.normaliserTexte("Zenith de Nantes"),
            null,
            null,
            null);

    serveur
        .expect(requestTo(OverpassSalleSourceTestUrl()))
        .andRespond(
            withSuccess(
                """
                {"elements":[{"type":"node","id":1,"tags":{
                  "name":"Zenith de Nantes",
                  "name:fr":"Zénith de Nantes Métropole",
                  "wikidata":"Q3550188",
                  "website":"https://zenith-nantes.com",
                  "capacity":"9000",
                  "addr:city":"Saint-Herblain"
                }}]}
                """,
                MediaType.APPLICATION_JSON));

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    EnrichissementSalle enrichissement = resultat.get(id);
    assertNotNull(enrichissement);
    assertEquals("Q3550188", enrichissement.wikidataId());
    assertEquals("Zénith de Nantes Métropole", enrichissement.nomAffiche());
    assertEquals("Saint-Herblain", enrichissement.ville());
    assertEquals("https://zenith-nantes.com", enrichissement.website());
    assertEquals(9000, enrichissement.capacite());
    serveur.verify();
  }

  private static org.hamcrest.Matcher<String> OverpassSalleSourceTestUrl() {
    return org.hamcrest.Matchers.containsString("overpass-api.de");
  }

  @Test
  void enrichirLot_nomNeCorrespondPas_retourneVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Salle Inconnue",
            fr.strapontin.core.Normalisation.normaliserTexte("Salle Inconnue"),
            null,
            null,
            null);

    serveur
        .expect(requestTo(OverpassSalleSourceTestUrl()))
        .andRespond(
            withSuccess(
                """
                {"elements":[{"type":"node","id":1,"tags":{"name":"Autre Salle"}}]}
                """,
                MediaType.APPLICATION_JSON));

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_erreurReseau_retourneVideSansException() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Zenith",
            fr.strapontin.core.Normalisation.normaliserTexte("Zenith"),
            null,
            null,
            null);

    serveur.expect(requestTo(OverpassSalleSourceTestUrl())).andRespond(withServerError());

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_nomAvecApostropheDroite_matchApostropheCourbeOsm() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "L'Empreinte",
            fr.strapontin.core.Normalisation.normaliserTexte("L'Empreinte"),
            null,
            null,
            null);

    serveur
        .expect(requestTo(OverpassSalleSourceTestUrl()))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        java.net.URLEncoder.encode(
                            "L['’]Empreinte", java.nio.charset.StandardCharsets.UTF_8))))
        .andRespond(
            withSuccess(
                """
                {"elements":[{"type":"node","id":1,"tags":{
                  "amenity":"music_venue",
                  "name":"L’Empreinte",
                  "operator":"Agglomération Grand Paris Sud"
                }}]}
                """,
                MediaType.APPLICATION_JSON));

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertNotNull(resultat.get(id));
    serveur.verify();
  }

  @Test
  void enrichirLot_lotVide_retourneVideSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of());

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }
}
