/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, "44", "Q123");

    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Sans département connu, aucune zone géographique ne peut être construite pour la requête
  // Overpass — la salle reste candidate pour la découverte Wikidata par nom, indépendante.
  @Test
  void enrichirLot_salleSansDept_ignoreeSansHttp() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, null);

    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseValideAvecWikidata_retourneEnrichissement() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
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

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    final EnrichissementSalle enrichissement = resultat.get(id);
    assertNotNull(enrichissement);
    assertEquals("Q3550188", enrichissement.wikidataId());
    assertEquals("Zénith de Nantes Métropole", enrichissement.nomAffiche());
    assertEquals("Saint-Herblain", enrichissement.ville());
    assertEquals("https://zenith-nantes.com", enrichissement.website());
    assertEquals(9000, enrichissement.capacite());
    serveur.verify();
  }

  @Test
  void enrichirLot_nomAvecGuillemet_echappeSansRompreLeLitteralOverpassQL() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nom = "Salle \"Le Central\"";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, nom, fr.strapontin.core.Normalisation.normaliserTexte(nom), null, "44", null);

    // Requête envoyée en corps form-urlencoded (data=...) : la comparaison doit passer par le
    // même encodage que le client, pas par la chaîne brute.
    final String fragmentAttendu =
        URLEncoder.encode(
            "name\"~\"Salle \\\"Le Central\\\"\",i", java.nio.charset.StandardCharsets.UTF_8);
    serveur
        .expect(requestTo(urlOverpass()))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(fragmentAttendu)))
        .andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_nomNeCorrespondPas_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
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

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Un échec HTTP est retenté une fois (NB_ESSAIS_MAX=2) avant d'abandonner — les deux appels
  // échouent ici pour vérifier le retry sans faire lever d'exception à l'appelant.
  @Test
  void enrichirLot_erreurReseau_retenteUneFoisPuisRetourneVideSansException() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Zenith",
            fr.strapontin.core.Normalisation.normaliserTexte("Zenith"),
            null,
            "75",
            null);

    serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
    serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Une fenêtre d'indisponibilité de l'instance publique (connexion refusée sur plusieurs lots
  // consécutifs) ne doit pas brûler tout le reste du cycle en retries voués à l'échec — le
  // 4e département, jamais interrogé (aucune expectation déclarée pour lui), le prouve.
  @Test
  void enrichirLot_echecsConsecutifs_interromptLeCycleSansInterrogerLaSuite() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final List<SalleAEnrichir> salles =
        List.of(
            salleDept("Salle 01", "01"),
            salleDept("Salle 02", "02"),
            salleDept("Salle 03", "03"),
            salleDept("Salle 04", "04"));

    for (int i = 0; i < 3; i++) {
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
    }

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(salles);

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  private static SalleAEnrichir salleDept(final String nom, final String dept) {
    return new SalleAEnrichir(
        UUID.randomUUID(),
        nom,
        fr.strapontin.core.Normalisation.normaliserTexte(nom),
        null,
        dept,
        null);
  }

  @Test
  void enrichirLot_nomAvecApostropheDroite_matchApostropheCourbeOsm() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
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
                        URLEncoder.encode(
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

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertNotNull(resultat.get(id));
    serveur.verify();
  }

  // Réessayer immédiatement après un 429 ne fait qu'ajouter de la charge à une instance déjà
  // rate-limitée — le backoff doit être nettement plus long qu'après une simple erreur réseau
  // isolée (504, timeout).
  @Test
  void delaiAvantRetry_429_backoffPlusLongQuUneErreurReseau() {
    final long delai429 =
        OverpassSalleSource.delaiAvantRetry(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                null));
    final long delaiErreurReseau =
        OverpassSalleSource.delaiAvantRetry(new IOException("connexion refusée"));

    assertTrue(delai429 > delaiErreurReseau);
  }

  @Test
  void enrichirLot_lotVide_retourneVideSansHttp() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(List.of());

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Le disjoncteur doit protéger tout le cycle, pas seulement l'appel courant : une fois ouvert
  // par un premier appel à enrichirLot, un second appel (simulant le lot de persistance suivant
  // dans le même cycle nocturne) ne doit déclencher aucun appel HTTP supplémentaire.
  @Test
  void enrichirLot_disjoncteurDejaOuvertParUnAppelPrecedent_neReinterrogePas() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    for (int i = 0; i < 3; i++) {
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
    }

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    final List<SalleAEnrichir> premierLot =
        List.of(
            salleDept("Salle 01", "01"), salleDept("Salle 02", "02"), salleDept("Salle 03", "03"));
    source.enrichirLot(premierLot);

    // Aucune expectation supplémentaire déclarée ci-dessus pour ce second appel — serveur.verify()
    // échouerait si le moindre appel HTTP était émis.
    final List<SalleAEnrichir> secondLot = List.of(salleDept("Salle 04", "04"));
    final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(secondLot);

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // preleverBilan (appelé une fois par cycle par EnrichissementSallesService.remonterBilans) doit
  // réarmer le disjoncteur pour le cycle suivant.
  @Test
  void preleverBilan_apresDisjoncteurOuvert_reinitialiseLeDisjoncteurPourLeCycleSuivant() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    for (int i = 0; i < 3; i++) {
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
      serveur.expect(requestTo(urlOverpass())).andRespond(withServerError());
    }
    serveur
        .expect(requestTo(urlOverpass()))
        .andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    source.enrichirLot(
        List.of(
            salleDept("Salle 01", "01"), salleDept("Salle 02", "02"), salleDept("Salle 03", "03")));
    source.preleverBilan();

    final Map<UUID, EnrichissementSalle> resultat =
        source.enrichirLot(List.of(salleDept("Salle 04", "04")));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Un seul déclenchement du disjoncteur (jusqu'à SEUIL_ECHECS_CONSECUTIFS × NB_ESSAIS_MAX appels)
  // ne doit pas suffire seul à franchir le seuil d'alerte "panne totale" par email.
  @Test
  void seuilAppelsMinAlerte_superieurAUnSeulDeclenchementDuDisjoncteur() {
    assertTrue(new OverpassSalleSource().seuilAppelsMinAlerte() > 6);
  }

  // Deux salles de départements différents doivent générer deux requêtes distinctes (une zone
  // géographique par département), pas un seul appel mélangeant les deux.
  @Test
  void enrichirLot_deuxDepartementsDifferents_genereDeuxRequetes() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id1 = UUID.randomUUID();
    final UUID id2 = UUID.randomUUID();
    final SalleAEnrichir salleParis =
        new SalleAEnrichir(
            id1,
            "Salle A",
            fr.strapontin.core.Normalisation.normaliserTexte("Salle A"),
            null,
            "75",
            null);
    final SalleAEnrichir salleNantes =
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

    final OverpassSalleSource source = new OverpassSalleSource(restClient);
    source.enrichirLot(List.of(salleParis, salleNantes));

    serveur.verify();
  }
}
