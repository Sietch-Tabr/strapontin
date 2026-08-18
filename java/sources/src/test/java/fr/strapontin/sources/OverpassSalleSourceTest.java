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

  private static org.hamcrest.Matcher<String> urlOverpass() {
    return org.hamcrest.Matchers.containsString("overpass-api.de");
  }

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
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, "44", "Q123");

    Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Sans département connu, aucune zone géographique ne peut être construite pour la requête
  // Overpass — la salle reste candidate pour la découverte Wikidata par nom, indépendante.
  @Test
  void enrichirLot_salleSansDept_ignoreeSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, null);

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
            "44",
            null);

    serveur
        .expect(requestTo(urlOverpass()))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("around%3A")))
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
            "75",
            null);

    serveur
        .expect(requestTo(urlOverpass()))
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

  // Un échec HTTP est retenté une fois (NB_ESSAIS_MAX=2) avant d'abandonner — les deux appels
  // échouent ici pour vérifier le retry sans faire lever d'exception à l'appelant.
  @Test
  void enrichirLot_erreurReseau_retenteUneFoisPuisRetourneVideSansException() {
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
            "75",
            null);

    serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
    serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());

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
            "77",
            null);

    serveur
        .expect(requestTo(urlOverpass()))
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

  // Réessayer immédiatement après un 429 ne fait qu'ajouter de la charge à une instance déjà
  // rate-limitée — le backoff doit être nettement plus long qu'après une simple erreur réseau
  // isolée (504, timeout).
  @Test
  void delaiAvantRetry_429_backoffPlusLongQuUneErreurReseau() {
    long delai429 =
        OverpassSalleSource.delaiAvantRetry(
            org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                null));
    long delaiErreurReseau =
        OverpassSalleSource.delaiAvantRetry(new java.io.IOException("connexion refusée"));

    assertTrue(delai429 > delaiErreurReseau);
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

  // Deux salles de départements différents doivent générer deux requêtes distinctes (une zone
  // géographique par département), pas un seul appel mélangeant les deux.
  @Test
  void enrichirLot_deuxDepartementsDifferents_genereDeuxRequetes() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    SalleAEnrichir salleParis =
        new SalleAEnrichir(
            id1,
            "Salle A",
            fr.strapontin.core.Normalisation.normaliserTexte("Salle A"),
            null,
            "75",
            null);
    SalleAEnrichir salleNantes =
        new SalleAEnrichir(
            id2,
            "Salle B",
            fr.strapontin.core.Normalisation.normaliserTexte("Salle B"),
            null,
            "44",
            null);

    serveur
        .expect(requestTo(urlOverpass()))
        .andRespond(
            withSuccess(
                """
                {"elements":[]}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(urlOverpass()))
        .andRespond(
            withSuccess(
                """
                {"elements":[]}
                """,
                MediaType.APPLICATION_JSON));

    OverpassSalleSource source = new OverpassSalleSource(restClient);
    source.enrichirLot(List.of(salleParis, salleNantes));

    serveur.verify();
  }
}
