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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WikidataSalleSourceTest {

  private static WikidataSalleSource source(
      RestClient restClient, Cache<String, EnrichissementSalle> cache) {
    return new WikidataSalleSource(restClient, cache);
  }

  @Test
  void nom_retourneWikidata() {
    assertEquals("wikidata", new WikidataSalleSource().nom());
  }

  // Régression : Overpass n'est plus un passage obligé pour les salles (instance publique
  // surchargée, cf. OverpassSalleSource) — sans wikidataId connu, la recherche par nom mwapi
  // doit être tentée, pas une simple mise de côté silencieuse.
  @Test
  void enrichirLot_salleSansWikidataId_utiliseDecouverteMwapi() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "L'Aeronef", "l aeronef", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("wikibase%3Amwapi")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("P625")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("P17")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q142")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q2831576"},
                   "itemLabel":{"type":"literal","value":"L'Aéronef"},
                   "communeLabel":{"type":"literal","value":"Lille"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q2831576", resultat.get(id).wikidataId());
    assertEquals("Lille", resultat.get(id).ville());
    serveur.verify();
  }

  // la recherche mwapi échoue quasi systématiquement sur les noms à suffixe de sous-salle
  // ("Zenith Toulouse Metropole" → 0 résultat en direct, "Zenith de Toulouse" trouvé aussitôt).
  // Ici : nom avec séparateur "-", premier appel (nom complet) sans résultat, repli sur le nom
  // tronqué au séparateur qui, lui, trouve un candidat.
  @Test
  void enrichirLot_rechercheCompleteEchoue_replieSurNomTronqueAuSeparateur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    String nomComplet = "Zenith Toulouse - Metropole";
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
            null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3576489"},
                   "itemLabel":{"type":"literal","value":"Zenith Toulouse"},
                   "communeLabel":{"type":"literal","value":"Toulouse"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q3576489", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // Les deux recherches (nom complet, nom tronqué) peuvent échouer : aucun candidat, mais aucune
  // exception, résultat vide.
  @Test
  void enrichirLot_rechercheCompleteEtTronqueeEchouent_retourneVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    String nomComplet = "Zenith Toulouse - Metropole";
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
            null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(0, resultat.size());
    serveur.verify();
  }

  // Régression : un nom propre français perd son identité après suppression des accents et peut
  // coïncider avec un nom anglais sans rapport — constaté en production : "Orléans Arena" (salle
  // française) normalisé en "orleans arena", strictement identique à "Orleans Arena" (Q3356467,
  // salle de Las Vegas, Nevada). Le filtre pays (P17=Q142 France) est la garantie qui empêche ce
  // cas — vérifié ici via le mock : si le serveur ne recevait jamais ce filtre, ce test ne le
  // détecterait pas puisque MockRestServiceServer ne fait aucune requête réelle ; la protection
  // réelle vient de la présence du triple dans la requête envoyée (vérifiée par
  // enrichirLot_salleSansWikidataId_utiliseDecouverteMwapi ci-dessus).
  @Test
  void construireRequeteDecouverte_contientLeFiltrePaysFrance() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Orléans Arena", "orleans arena", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("wdt%3AP17+wd%3AQ142")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // La recherche par nom peut retourner une entité administrative homonyme (région, département,
  // commune) plutôt que le lieu physique recherché — la requête doit les exclure explicitement.
  @Test
  void construireRequeteDecouverte_excluLesEntitesAdministratives() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Summum", "summum", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q3455524")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q6465")))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q484170")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // Régression : le label canonique Wikidata omet souvent l'article défini français porté par le
  // nom Strapontin (ex. salle "L'Olympia" ↔ Wikidata "Olympia", Q915451, vérifié en juillet 2026).
  // La correspondance floue (CorrespondanceSalle.estDoublonSalle, containment ratio) absorbe cet
  // écart sans traitement spécifique de l'article.
  @Test
  void enrichirLot_decouverte_ignoreArticleDefiniFrancaisPourLaComparaison() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "L'Olympia", "l olympia", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q915451"},
                   "itemLabel":{"type":"literal","value":"Olympia"},
                   "communeLabel":{"type":"literal","value":"9e arrondissement de Paris"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q915451", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // Régression : les grandes salles changent régulièrement de nom commercial (sponsoring) —
  // Wikidata conserve souvent l'ancien nom comme libellé principal, le nom actuel n'étant qu'un
  // alias (constaté en production : "Accor Arena" n'est qu'un alias de l'entité dont le libellé
  // principal Wikidata est resté "Palais Omnisports de Paris-Bercy", Q537720). Sans vérifier les
  // alias, ces salles ne seraient jamais enrichies malgré une entrée Wikidata existante.
  @Test
  void enrichirLot_decouverte_matchSurAliasSiLibellePrincipalDifferent() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Accor Arena", "accor arena", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q537720"},
                   "itemLabel":{"type":"literal","value":"Palais Omnisports de Paris-Bercy"},
                   "aliases":{"type":"literal","value":"Accor Arena|Paris-Bercy|POPB|Arena Bercy"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    EnrichissementSalle enrichissement = resultat.get(id);
    assertEquals("Q537720", enrichissement.wikidataId());
    assertEquals("Accor Arena", enrichissement.nomAffiche());
    serveur.verify();
  }

  // Régression : les noms de salle Strapontin portent souvent un suffixe descriptif absent du
  // libellé Wikidata (ex. "Zenith Paris - la Villette" côté TM/Eventim ↔ "Zénith Paris" côté
  // Wikidata, constaté en production). Une correspondance exacte échouerait ; le Jaccard sur
  // tokens de CorrespondanceSalle.estDoublonSalle absorbe ce cas (tokens communs "zenith"+"paris").
  @Test
  void enrichirLot_decouverte_absorbeSuffixeDescriptifAbsentDeWikidata() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Zenith Paris - la Villette", "zenith paris la villette", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q247566"},
                   "itemLabel":{"type":"literal","value":"Zénith Paris"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q247566", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // "Dôme de Paris" (nom Strapontin actuel) n'a ni libellé principal ("Palais des
  // sports") ni alias exact correspondant sur Wikidata — l'alias le plus proche, "Dôme de Paris
  // - Palais des Sports", combine ancien et nouveau nom et est trop long pour satisfaire le
  // containment/Jaccard symétrique de estDoublonSalle (Jaccard=0.4, ratio de containment=0.41,
  // tous deux sous les seuils 0.5). Reproduit avec les données réelles interrogées manuellement
  // sur query.wikidata.org (Q2234305).
  @Test
  void enrichirLot_decouverte_absorbeAliasCombinantAncienEtNouveauNom() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Dôme de Paris", "dome de paris", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q2234305"},
                   "itemLabel":{"type":"literal","value":"Palais des sports"},
                   "aliases":{"type":"literal",
                     "value":"Palais des sports de Paris|Dôme de Paris - Palais des Sports"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q2234305", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // constaté en production — le repli sur nom tronqué peut retenir un candidat au nom
  // générique partagé par plusieurs communes ("Collegiale Saint" → "collégiale Saint-Agricol
  // d'Avignon" en rang 0, alors que la salle recherchée "Collegiale Saint-Mexme Chinon" est à
  // Chinon). Vérifié en direct sur l'API Wikidata (mwapi:search "Collegiale Saint") : ce candidat
  // existe réellement et passe la correspondance de nom (tokens "collegiale"+"saint" communs).
  @Test
  void enrichirLot_decouverte_rejetteCandidatVilleIncompatible() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    String nomComplet = "Collegiale Saint-Mexme Chinon";
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            "Chinon",
            "37",
            null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q2983940"},
                   "itemLabel":{"type":"literal","value":"collégiale Saint-Agricol d'Avignon"},
                   "communeLabel":{"type":"literal","value":"Avignon"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverte_villeConnueEtCorrespondante_accepteCandidat() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Accor Arena", "accor arena", "Paris", "75", null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q537720"},
                   "itemLabel":{"type":"literal","value":"Accor Arena"},
                   "communeLabel":{"type":"literal","value":"Paris"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q537720", resultat.get(id).wikidataId());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteNomDifferent_ignoreCandidat() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Le Zenith", "le zenith", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q999"},
                   "itemLabel":{"type":"literal","value":"Un gymnase quelconque"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteAucunCandidat_retourneVide() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Salle Inconnue", "salle inconnue", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseValide_retourneEnrichissement() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188");

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3550188"},
                   "itemLabel":{"type":"literal","value":"Zénith de Nantes Métropole"},
                   "communeLabel":{"type":"literal","value":"Saint-Herblain"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    EnrichissementSalle enrichissement = resultat.get(id);
    assertNotNull(enrichissement);
    assertEquals("Q3550188", enrichissement.wikidataId());
    assertEquals("Zénith de Nantes Métropole", enrichissement.nomAffiche());
    assertEquals("Saint-Herblain", enrichissement.ville());
    serveur.verify();
  }

  @Test
  void enrichirLot_resultatEnCache_retourneSansHttp() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    Cache<String, EnrichissementSalle> cache = Caffeine.newBuilder().build();
    EnrichissementSalle attendu =
        new EnrichissementSalle("Q3550188", "Zénith", "Nantes", null, null);
    cache.put("Q3550188", attendu);

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188");

    WikidataSalleSource src = source(restClient, cache);
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(attendu, resultat.get(id));
    serveur.verify();
  }

  @Test
  void enrichirLot_429AvecRetryAfter_reessaieEtReussit() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188");

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0").body(""));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3550188"},
                   "itemLabel":{"type":"literal","value":"Zénith"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Zénith", resultat.get(id).nomAffiche());
    serveur.verify();
  }

  @Test
  void enrichirLot_503Persistant_abandonneApres3Tentatives() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    UUID id = UUID.randomUUID();
    SalleAEnrichir salle = new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188");

    for (int i = 0; i < 3; i++) {
      serveur
          .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
          .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));
    }

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_batchSuperieurA50_decoupeEnPlusieursRequetes() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    List<SalleAEnrichir> salles = new java.util.ArrayList<>();
    for (int i = 0; i < 60; i++) {
      salles.add(
          new SalleAEnrichir(UUID.randomUUID(), "Salle" + i, "salle" + i, null, null, "Q" + i));
    }

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(salles);

    serveur.verify();
  }
}
