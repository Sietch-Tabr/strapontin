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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

class WikidataSalleSourceTest {

  private static WikidataSalleSource source(
      final RestClient restClient, final Cache<String, EnrichissementSalle> cache) {
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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "L'Aeronef", "l aeronef", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("wikibase%3Amwapi")))
        .andExpect(
            MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("P625")))
        .andExpect(
            MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("P17")))
        .andExpect(
            MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("Q142")))
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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nomComplet = "Zenith Toulouse - Metropole";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q3576489", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // Convention de nommage fréquente côté sources : la salle est suffixée de sa ville sans
  // séparateur ("La Belle Electrique Grenoble") — vérifié en direct sur l'API Wikidata, la
  // recherche mwapi échoue sur le nom complet mais trouve l'entité sur "La Belle Electrique" seul.
  @Test
  void enrichirLot_rechercheCompleteEchoue_replieSansDernierMot() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nomComplet = "La Belle Electrique Grenoble";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
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
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q61854481"},
                   "itemLabel":{"type":"literal","value":"La Belle Electrique"},
                   "communeLabel":{"type":"literal","value":"Grenoble"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q61854481", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // Régression : « La Nouvelle Vague » (Saint-Malo) ne doit jamais être rapprochée de « La
  // Nouvelle République » (un journal), un candidat sans commune ni code postal connus qui ne
  // matche que sur le pivot générique "nouvelle" — aucune donnée de localisation pour un contrôle
  // croisé indépendant.
  @Test
  void enrichirLot_pivotGeneriqueSansLocalisation_rejetteLeCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nomComplet = "La Nouvelle Vague";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
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
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3211144"},
                   "itemLabel":{"type":"literal","value":"La Nouvelle République"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(0, resultat.size());
    serveur.verify();
  }

  // Garde-fou : un nom de deux mots ne doit jamais être tronqué à un seul mot générique — aucun
  // second appel HTTP ne doit être tenté (vérifié par MockRestServiceServer : un appel non
  // attendu ferait échouer le test).
  @Test
  void enrichirLot_nomDeuxMots_nePasTronquerAUnMotUnique() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Espace Nova", "espace nova", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Les trois recherches (nom complet, nom tronqué au séparateur, nom sans dernier mot) peuvent
  // toutes échouer : aucun candidat, mais aucune exception, résultat vide.
  @Test
  void enrichirLot_rechercheCompleteEtTronqueeEchouent_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nomComplet = "Zenith Toulouse - Metropole";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            null,
            null,
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
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Orléans Arena", "orleans arena", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("wdt%3AP17+wd%3AQ142")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // La recherche par nom peut retourner une entité administrative homonyme (région, département,
  // commune) plutôt que le lieu physique recherché — la requête doit les exclure explicitement.
  @Test
  void construireRequeteDecouverte_excluLesEntitesAdministratives() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Summum", "summum", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q3455524")))
        .andExpect(
            MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("Q6465")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q484170")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // Régression : le label canonique Wikidata omet souvent l'article défini français porté par le
  // nom Strapontin (ex. salle "L'Olympia" ↔ Wikidata "Olympia", Q915451, vérifié en juillet 2026).
  // La correspondance floue (CorrespondanceSalle.estDoublonSalle, containment ratio) absorbe cet
  // écart sans traitement spécifique de l'article.
  @Test
  void enrichirLot_decouverte_ignoreArticleDefiniFrancaisPourLaComparaison() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "L'Olympia", "l olympia", null, null, null, null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Accor Arena", "accor arena", null, null, null, null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    final EnrichissementSalle enrichissement = resultat.get(id);
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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Zenith Paris - la Villette",
            "zenith paris la villette",
            null,
            null,
            null,
            null,
            null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Dôme de Paris", "dome de paris", null, null, null, null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

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
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final String nomComplet = "Collegiale Saint-Mexme Chinon";
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomComplet,
            fr.strapontin.core.Normalisation.normaliserTexte(nomComplet),
            "Chinon",
            "37",
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
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q2983940"},
                   "itemLabel":{"type":"literal","value":"collégiale Saint-Agricol d'Avignon"},
                   "communeLabel":{"type":"literal","value":"Avignon"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));
    // repli sans dernier mot ("Collegiale Saint-Mexme Chinon" → "Collegiale Saint-Mexme"),
    // également infructueux
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Régression : la ville seule ne bloque rien quand elle est inconnue au moment de
  // l'enrichissement (cas fréquent d'une salle nouvellement créée) — le département, connu dès la
  // création de la salle à partir d'un concert réel, doit alors prendre le relais pour rejeter un
  // candidat sans rapport (ex. une gare/un arrêt de tram matché par erreur, ville non renseignée).
  @Test
  void enrichirLot_decouverte_rejetteCandidatDeptIncompatibleVilleInconnue() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Palais des Congres", "palais des congres", null, "04", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q98573438"},
                   "itemLabel":{"type":"literal","value":"Palais des Congres"},
                   "communeLabel":{"type":"literal","value":"Bordeaux"},
                   "codePostal":{"type":"literal","value":"33000"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));
    // repli sans dernier mot ("Palais des Congres" → "Palais des"), également infructueux
    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  // Non-régression : dept connu et cohérent (ou code postal absent côté Wikidata) n'empêche pas
  // l'acceptation d'un candidat par ailleurs valide.
  @Test
  void enrichirLot_decouverte_deptConnuEtCorrespondant_accepteCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Accor Arena", "accor arena", null, "75", null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q537720"},
                   "itemLabel":{"type":"literal","value":"Accor Arena"},
                   "communeLabel":{"type":"literal","value":"Paris"},
                   "codePostal":{"type":"literal","value":"75012"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q537720", resultat.get(id).wikidataId());
    serveur.verify();
  }

  // Une gare/un arrêt de tram homonyme du nom recherché ne doit jamais être proposé comme
  // candidat — constaté en production (gare de Villepinte, arrêt de tram bordelais).
  @Test
  void construireRequeteDecouverte_excluLesInfrastructuresDeTransport() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q55488")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q2175765")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // Un bar/restaurant homonyme situé sur le même site qu'une vraie salle de spectacle ne doit
  // jamais être proposé comme candidat — constaté en production (bar restaurant de la Saline
  // Royale, distinct du monument éponyme accueillant les concerts).
  @Test
  void construireRequeteDecouverte_excluLesCommerces() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Saline Royale", "saline royale", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q187456")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q11707")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(List.of(salle));

    serveur.verify();
  }

  // Régression : une salle réelle sans coordonnées Wikidata (ex. « salle Paul-Fort », Nantes)
  // était structurellement exclue par l'exigence de coordonnées seule, ne laissant qu'un
  // homonyme sans rapport (« salle Paul Bouin ») comme candidat retourné. Le type « bâtiment de
  // salle de concert » (Q1060829) sert d'alternative vérifiée aux coordonnées.
  @Test
  void construireRequeteDecouverte_accepteLeTypeBatimentSalleDeConcertSansCoordonnees() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Salle Paul Fort", "salle paul fort", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Q1060829")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q57315616"},
                   "itemLabel":{"type":"literal","value":"salle Paul-Fort"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q57315616", resultat.get(id).wikidataId());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverte_villeConnueEtCorrespondante_accepteCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Accor Arena", "accor arena", "Paris", "75", null, null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Q537720", resultat.get(id).wikidataId());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteNomDifferent_ignoreCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Zenith", "le zenith", null, null, null, null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_decouverteAucunCandidat_retourneVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Salle Inconnue", "salle inconnue", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseValide_retourneEnrichissement() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    final EnrichissementSalle enrichissement = resultat.get(id);
    assertNotNull(enrichissement);
    assertEquals("Q3550188", enrichissement.wikidataId());
    assertEquals("Zénith de Nantes Métropole", enrichissement.nomAffiche());
    assertEquals("Saint-Herblain", enrichissement.ville());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseAvecCoordonnees_extraitLatitudeEtLongitude() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3550188"},
                   "itemLabel":{"type":"literal","value":"Zénith de Nantes Métropole"},
                   "communeLabel":{"type":"literal","value":"Saint-Herblain"},
                   "coord":{"type":"literal","value":"Point(-1.573 47.238)"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    final EnrichissementSalle enrichissement = resultat.get(id);
    assertEquals(47.238, enrichissement.latitude());
    assertEquals(-1.573, enrichissement.longitude());
    serveur.verify();
  }

  @Test
  void enrichirLot_reponseSansCoordonnees_latitudeEtLongitudeNull() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3550188"},
                   "itemLabel":{"type":"literal","value":"Zénith de Nantes Métropole"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    final EnrichissementSalle enrichissement = resultat.get(id);
    assertNull(enrichissement.latitude());
    assertNull(enrichissement.longitude());
    serveur.verify();
  }

  // Régression : la découverte par nom (Q-ID inconnu) doit aussi extraire les coordonnées, pas
  // seulement le mode batch (Q-ID déjà connu) — deux requêtes SPARQL distinctes.
  @Test
  void enrichirLot_decouverteAvecCoordonnees_extraitLatitudeEtLongitude() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "L'Aeronef", "l aeronef", null, null, null, null, null);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q2831576"},
                   "itemLabel":{"type":"literal","value":"L'Aéronef"},
                   "communeLabel":{"type":"literal","value":"Lille"},
                   "coord":{"type":"literal","value":"Point(3.045 50.641)"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    final EnrichissementSalle enrichissement = resultat.get(id);
    assertEquals(50.641, enrichissement.latitude());
    assertEquals(3.045, enrichissement.longitude());
    serveur.verify();
  }

  @Test
  void enrichirLot_resultatEnCache_retourneSansHttp() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final Cache<String, EnrichissementSalle> cache = Caffeine.newBuilder().build();
    final EnrichissementSalle attendu =
        new EnrichissementSalle("Q3550188", "Zénith", "Nantes", null, null);
    cache.put("Q3550188", attendu);

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

    final WikidataSalleSource src = source(restClient, cache);
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(attendu, resultat.get(id));
    serveur.verify();
  }

  @Test
  void enrichirLot_429AvecRetryAfter_reessaieEtReussit() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals(1, resultat.size());
    assertEquals("Zénith", resultat.get(id).nomAffiche());
    serveur.verify();
  }

  @Test
  void enrichirLot_503Persistant_abandonneApres3Tentatives() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", null, null, "Q3550188", null, null);

    for (int i = 0; i < 3; i++) {
      serveur
          .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
          .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));
    }

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    serveur.verify();
  }

  @Test
  void enrichirLot_batchSuperieurA50_decoupeEnPlusieursRequetes() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final List<SalleAEnrichir> salles = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      salles.add(
          new SalleAEnrichir(
              UUID.randomUUID(), "Salle" + i, "salle" + i, null, null, "Q" + i, null, null));
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

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    src.enrichirLot(salles);

    serveur.verify();
  }

  // --- typesInvalides (revalidation des wikidata_id déjà stockés) ---

  @Test
  void typesInvalides_typeExclu_retourneLeQid() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q110597455"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Set<String> resultat = src.typesInvalides(List.of("Q110597455"));

    assertEquals(Set.of("Q110597455"), resultat);
    serveur.verify();
  }

  @Test
  void typesInvalides_typeValide_retourneEnsembleVide() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Set<String> resultat = src.typesInvalides(List.of("Q537720"));

    assertEquals(Set.of(), resultat);
    serveur.verify();
  }

  // Cas réel documenté en production (#1218) : "Alhambra" (nom/ville/dept compatibles) matchait
  // à tort l'ancien théâtre disparu du 11e (Q4724424) au lieu de la salle de concert active du
  // 10e — ni la ville ni le département (identiques des deux côtés, Paris/75) ne peuvent détecter
  // cette incohérence, seules les coordonnées GPS déjà connues de la salle le révèlent.
  @Test
  void enrichirLot_decouverteCoordonneesDivergentes_rejetteEtSignaleAmbiguite() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Alhambra", "alhambra", "Paris", "75", null, 48.8716, 2.3616);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q4724424"},
                   "itemLabel":{"type":"literal","value":"Alhambra"},
                   "communeLabel":{"type":"literal","value":"Paris"},
                   "coord":{"type":"literal","value":"Point(2.377 48.867)"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertTrue(resultat.isEmpty());
    assertEquals(Set.of(id), src.preleverAmbiguites());
    assertEquals(Set.of(), src.preleverAmbiguites(), "prélevé — ne doit pas resignaler");
    serveur.verify();
  }

  // Contre-épreuve : un candidat dont les coordonnées sont proches (quelques dizaines de mètres)
  // de celles déjà connues doit toujours être accepté normalement.
  @Test
  void enrichirLot_decouverteCoordonneesProches_accepteLeCandidat() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    final RestClient restClient = builder.build();

    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Alhambra", "alhambra", "Paris", "75", null, 48.8716, 2.3616);

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("query.wikidata.org")))
        .andRespond(
            withSuccess(
                """
                {"results":{"bindings":[
                  {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q3406734"},
                   "itemLabel":{"type":"literal","value":"Alhambra"},
                   "communeLabel":{"type":"literal","value":"Paris"},
                   "coord":{"type":"literal","value":"Point(2.3618 48.8718)"},
                   "num":{"type":"literal","value":"0"}}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    final WikidataSalleSource src = source(restClient, Caffeine.newBuilder().build());
    final Map<UUID, EnrichissementSalle> resultat = src.enrichirLot(List.of(salle));

    assertEquals("Q3406734", resultat.get(id).wikidataId());
    assertEquals(Set.of(), src.preleverAmbiguites());
    serveur.verify();
  }
}
