/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClient;

/**
 * Client SPARQL Wikidata pour l'enrichissement des salles (étape 2).
 *
 * <p>Deux modes selon que le Q-ID est déjà connu :
 *
 * <ul>
 *   <li>{@code wikidataId != null} (fourni par {@link OverpassSalleSource} ou déjà stocké en base)
 *       : requête batch classique (clause {@code VALUES}, ≤50)
 *   <li>{@code wikidataId == null} : découverte par recherche de nom via {@code SERVICE
 *       wikibase:mwapi} (une requête par salle, non batchable) — Overpass n'est plus un passage
 *       obligé pour les salles (son instance publique traverse un incident de surcharge documenté,
 *       voir {@link OverpassSalleSource}). <strong>Pas de filtre de type positif</strong>
 *       (contrairement à {@link WikidataArtisteSource}) : l'ontologie Wikidata des salles de
 *       spectacle est hétérogène (ex. « salle de spectacles polyvalente » Q1763828 est sous-classe
 *       P279 de « gymnase » Q14092), un filtre transitif risquerait de faire correspondre des
 *       équipements sportifs. Un filtre négatif exclut en revanche les entités de type
 *       région/département/commune (P31 région administrative, département français, commune
 *       française) ainsi que les infrastructures de transport (gare, arrêt de tram) : une recherche
 *       par nom peut sinon retourner une entité administrative ou de transport homonyme plutôt que
 *       le lieu physique recherché — constaté en production sur une gare (Villepinte) et un arrêt
 *       de tram (Bordeaux) retenus à tort. Le département de la commune du candidat (déduit de son
 *       code postal, P281) est également confronté au département connu de la salle Strapontin
 *       quand les deux sont disponibles (voir {@link #deptIncompatible}) — {@code salles.ville}
 *       peut rester vide au moment de cet enrichissement quand {@code salles.dept} est déjà fiable
 *       (renseigné dès la création à partir d'un concert réel). En contrepartie, la requête exige
 *       {@code wdt:P625} (coordonnées géographiques) : sans ce garde-fou, une recherche par nom
 *       seul peut retourner en premier résultat une œuvre culturelle homonyme (constaté en
 *       production : pour « L'Olympia », le premier candidat mwapi est un <em>album</em> d'Émilie
 *       Simon sorti en 2007, pas la salle) — un album/single/livre n'a pas de coordonnées, une
 *       salle en a toujours une. Exception vérifiée : un candidat typé {@code wd:Q1060829}
 *       (bâtiment de salle de concert) est accepté même sans coordonnées — constaté en production
 *       sur « salle Paul-Fort » (Nantes), correcte mais structurellement exclue par l'exigence de
 *       coordonnées seule, laissant un homonyme sans rapport (« salle Paul Bouin », Basse-Goulaine)
 *       devenir l'unique candidat retourné. La désambiguïsation finale réutilise {@link
 *       CorrespondanceSalle#estDoublonSalle} (module {@code core}, même algorithme que {@code
 *       ReconciliateurSalles} côté worker : Levenshtein ≥ 0.75, Jaccard tokens, critère pivot)
 *       plutôt qu'une simple égalité de chaîne — nécessaire pour absorber à la fois l'article
 *       défini français (« L'Olympia » ↔ « Olympia », via containment) et les suffixes descriptifs
 *       fréquents côté Strapontin absents du libellé Wikidata (« Zenith Paris - la Villette » ↔ «
 *       Zénith Paris », via Jaccard tokens). La comparaison porte aussi sur les
 *       <strong>alias</strong> ({@code skos:altLabel}) : les grandes salles changent régulièrement
 *       de nom commercial (sponsoring) et Wikidata conserve souvent l'ancien nom comme libellé
 *       principal, le nom actuel n'étant qu'un alias — constaté en production : « Accor Arena »
 *       (nom Strapontin, actuel) n'est qu'un alias de l'entité dont le libellé principal Wikidata
 *       est resté « Palais Omnisports de Paris-Bercy ». Sans vérifier les alias, ces salles ne
 *       seraient jamais enrichies malgré une entrée Wikidata existante. Réutiliser l'algorithme
 *       flou du projet plutôt qu'une correspondance exacte reste raisonnablement sûr ici : les
 *       candidats mwapi sont déjà restreints aux résultats les plus pertinents pour le nom
 *       recherché, et les seuils (Jaccard 0.5, pivot ≥7 chars) ont été calibrés dans le projet
 *       précisément pour rejeter les homonymes inter-villes (« zenith bordeaux » vs « zenith caen
 *       »). <strong>Ce calibrage ne couvre pas les homonymes internationaux</strong> — un nom
 *       propre français perd son identité après suppression des accents et peut coïncider avec un
 *       nom anglais sans rapport (constaté en production : « Orléans Arena » → « orleans arena »,
 *       strictement identique à « Orleans Arena » Q3356467, une salle de Las Vegas, Nevada —
 *       correspondance exacte immédiate, aucun seuil flou impliqué). La requête exige donc {@code
 *       wdt:P17 wd:Q142} (pays = France) : Strapontin ne suit que des concerts français
 *       (TM/Eventim/Digitick France), toute salle hors de ce périmètre est nécessairement une
 *       fausse correspondance.
 * </ul>
 *
 * @since 2.6.0
 * @author Kwaite
 */
public class WikidataSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final int TAILLE_LOT_MAX = 50;

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementSalle> cache;
  private final WikidataSalleDecouverte decouverte;

  /**
   * Retourne le bilan agrégé des appels SPARQL Wikidata depuis le dernier
   * prélèvement.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public fr.strapontin.core.BilanFetch preleverBilan() {
    return client.preleverBilan();
  }

  /** Crée une instance avec un client SPARQL par défaut et un cache 24h. */
  public WikidataSalleSource() {
    this(
        new ClientSparqlWikidata(),
        Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).maximumSize(10_000).build());
  }

  /**
   * Crée une instance avec un RestClient et un cache personnalisés (injection
   * de test).
   *
   * @param restClient client HTTP à utiliser
   * @param cache cache Caffeine à utiliser
   */
  public WikidataSalleSource(
      final RestClient restClient, final Cache<String, EnrichissementSalle> cache) {
    this(new ClientSparqlWikidata(restClient), cache);
  }

  private WikidataSalleSource(
      final ClientSparqlWikidata client, final Cache<String, EnrichissementSalle> cache) {
    this.client = client;
    this.cache = cache;
    this.decouverte = new WikidataSalleDecouverte(client, cache);
  }

  @Override
  public String nom() {
    return "wikidata";
  }

  @Override
  public Map<UUID, EnrichissementSalle> enrichirLot(final List<SalleAEnrichir> salles) {
    final Map<UUID, EnrichissementSalle> resultats = new HashMap<>();

    final List<SalleAEnrichir> connues =
        salles.stream().filter(s -> s.wikidataId() != null).toList();
    final List<SalleAEnrichir> aDecouvrir =
        salles.stream().filter(s -> s.wikidataId() == null).toList();

    final List<SalleAEnrichir> nonCachees = new ArrayList<>();
    for (final SalleAEnrichir s : connues) {
      final EnrichissementSalle depuisCache = cache.getIfPresent(s.wikidataId());
      if (depuisCache != null) {
        resultats.put(s.id(), depuisCache);
      } else {
        nonCachees.add(s);
      }
    }

    for (int i = 0; i < nonCachees.size(); i += TAILLE_LOT_MAX) {
      final List<SalleAEnrichir> lot =
          nonCachees.subList(i, Math.min(i + TAILLE_LOT_MAX, nonCachees.size()));
      resultats.putAll(enrichirLotConnu(lot));
    }

    for (final SalleAEnrichir salle : aDecouvrir) {
      decouverte.decouvrir(salle).ifPresent(e -> resultats.put(salle.id(), e));
    }

    return resultats;
  }

  // --- Mode batch (Q-ID connu) ---

  private Map<UUID, EnrichissementSalle> enrichirLotConnu(final List<SalleAEnrichir> lot) {
    final Map<String, UUID> qidVersUuid = new HashMap<>();
    for (final SalleAEnrichir s : lot) {
      qidVersUuid.put(s.wikidataId(), s.id());
    }

    final String sparql =
        construireRequeteSparql(lot.stream().map(SalleAEnrichir::wikidataId).toList());
    final Map<String, Object> data = client.executer(sparql);
    if (data == null) {
      return Map.of();
    }
    return mapperReponse(data, qidVersUuid);
  }

  private static String construireRequeteSparql(final List<String> qIds) {
    final String values =
        qIds.stream()
            .filter(ClientSparqlWikidata::estQIdValide)
            .map(q -> "wd:" + q)
            .collect(Collectors.joining(" "));
    return """
    SELECT ?item ?itemLabel ?communeLabel ?coord WHERE {
      VALUES ?item { %s }
      OPTIONAL {
        ?item wdt:P131 ?commune .
        ?commune rdfs:label ?communeLabel .
        FILTER(LANG(?communeLabel) = "fr")
      }
      OPTIONAL { ?item wdt:P625 ?coord }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    """
        .formatted(values);
  }

  // Le record retourné porte les champs du binding courant — pas d'instance
  // hissable hors boucle.
  @SuppressWarnings({"unchecked", "PMD.AvoidInstantiatingObjectsInLoops"})
  private Map<UUID, EnrichissementSalle> mapperReponse(
      final Map<String, Object> data, final Map<String, UUID> qidVersUuid) {
    final Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    final Map<String, Object> results = (Map<String, Object>) data.get("results");
    if (results == null) {
      return resultats;
    }
    final List<Map<String, Object>> bindings = (List<Map<String, Object>>) results.get("bindings");
    if (bindings == null) {
      return resultats;
    }

    for (final Map<String, Object> binding : bindings) {
      final String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
      if (itemUri == null) {
        continue;
      }
      final String qid = itemUri.substring(itemUri.lastIndexOf('/') + 1);
      final UUID salleId = qidVersUuid.get(qid);
      if (salleId == null) {
        continue;
      }

      final double[] coord =
          ClientSparqlWikidata.parserCoordonnees(
              ClientSparqlWikidata.valeurBinding(binding, "coord"));
      final EnrichissementSalle enrichissement =
          new EnrichissementSalle(
              qid,
              ClientSparqlWikidata.valeurBinding(binding, "itemLabel"),
              ClientSparqlWikidata.valeurBinding(binding, "communeLabel"),
              null,
              null,
              coord != null ? coord[1] : null,
              coord != null ? coord[0] : null);
      resultats.put(salleId, enrichissement);
      cache.put(qid, enrichissement);
    }
    return resultats;
  }

  /**
   * Revalide le type Wikidata (P31) des {@code wikidataId} déjà stockés contre {@link
   * WikidataSalleDecouverte#TYPES_EXCLUS} — un garde-fou de type introduit après coup (ex.
   * bar/restaurant, #1084) ne s'applique qu'aux nouvelles découvertes, jamais rétroactivement aux
   * {@code wikidata_id} posés avant son existence.
   *
   * @param wikidataIds Q-IDs déjà stockés à revalider
   * @return le sous-ensemble de {@code wikidataIds} dont le type Wikidata est désormais exclu
   */
  public Set<String> typesInvalides(final List<String> wikidataIds) {
    final Set<String> invalides = new HashSet<>();
    for (int i = 0; i < wikidataIds.size(); i += TAILLE_LOT_MAX) {
      final List<String> lot =
          wikidataIds.subList(i, Math.min(i + TAILLE_LOT_MAX, wikidataIds.size()));
      invalides.addAll(interrogerTypesInvalides(lot));
    }
    return invalides;
  }

  @SuppressWarnings("unchecked")
  private Set<String> interrogerTypesInvalides(final List<String> qIds) {
    final String sparql = construireRequeteTypesInvalides(qIds);
    final Map<String, Object> data = client.executer(sparql);
    if (data == null) {
      return Set.of();
    }

    final Set<String> invalides = new HashSet<>();
    final Map<String, Object> results = (Map<String, Object>) data.get("results");
    final List<Map<String, Object>> bindings =
        results == null ? null : (List<Map<String, Object>>) results.get("bindings");
    if (bindings == null) {
      return invalides;
    }
    for (final Map<String, Object> binding : bindings) {
      final String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
      if (itemUri == null) {
        continue;
      }
      invalides.add(itemUri.substring(itemUri.lastIndexOf('/') + 1));
    }
    return invalides;
  }

  private static String construireRequeteTypesInvalides(final List<String> qIds) {
    final String items =
        qIds.stream()
            .filter(ClientSparqlWikidata::estQIdValide)
            .map(q -> "wd:" + q)
            .collect(Collectors.joining(" "));
    final String typesExclusSparql =
        WikidataSalleDecouverte.TYPES_EXCLUS.stream()
            .map(qid -> "wd:" + qid)
            .collect(Collectors.joining(" "));
    return """
    SELECT DISTINCT ?item WHERE {
      VALUES ?item { %s }
      ?item wdt:P31 ?type .
      VALUES ?type { %s }
    }
    """
        .formatted(items, typesExclusSparql);
  }
}
