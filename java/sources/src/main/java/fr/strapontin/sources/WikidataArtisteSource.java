/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Client SPARQL Wikidata pour l'enrichissement des artistes (étape 3 +).
 *
 * Deux modes selon que le Q-ID est déjà connu :
 *
 * <ul>
 *   <li>{@code wikidataId != null} : requête batch classique (clause {@code VALUES}, ≤50)
 *   <li>{@code wikidataId == null} : découverte par recherche de nom via {@code SERVICE
 *       wikibase:mwapi} (une requête par artiste, non batchable), filtrée par les types
 *       intrinsèques Wikidata (humain+musicien, humain+humoriste depuis, ou groupe) —
 *       <strong>jamais validée contre l'attractionId Ticketmaster existant</strong> : Wikidata
 *       fait autorité pour l'identité de l'artiste, indépendamment de TM (décision actée, cf. plan
 *       du jalon 2.6.0). Le P6262 éventuellement porté par le Q-ID trouvé sert ensuite à corriger
 *       notre lien vers TM, jamais l'inverse. En cas d'homonymie groupe/personne (ex. « Van Halen
 *       ») détectée via la diversité de {@code ?typeMatch} parmi les meilleurs candidats, aucun
 *       Q-ID n'est retenu plutôt que de deviner — cf. {@link #decouvrir}. </ul>
 *
 * @since 2.6.0
 * @author Kwaite
 */
public class WikidataArtisteSource
    implements SourceEnrichissementArtiste, SourceAvecBilanFetch, SourceAvecAmbiguitesDecouverte {

  private static final Logger LOG = LoggerFactory.getLogger(WikidataArtisteSource.class);

  // Rempli par decouvrir() sur ambiguïté humain/groupe, vidé par preleverAmbiguites() — même
  // patron "prélever" que preleverBilan (BilanFetch), pas de dépendance JDBC dans ce module.
  private final Set<UUID> ambiguites = new HashSet<>();

  @Override
  public Set<UUID> preleverAmbiguites() {
    final Set<UUID> resultat = new HashSet<>(ambiguites);
    ambiguites.clear();
    return resultat;
  }

  /**
   * Retourne le bilan agrégé des appels SPARQL Wikidata depuis le dernier prélèvement.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public fr.strapontin.core.BilanFetch preleverBilan() {
    return client.preleverBilan();
  }

  private static final int TAILLE_LOT_MAX = 50;

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementArtiste> cache;

  /**
   * Crée une instance avec un client SPARQL par défaut et un cache 24h.
   */
  public WikidataArtisteSource() {
    this(
        new ClientSparqlWikidata(),
        Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).maximumSize(10_000).build());
  }

  /**
   * Crée une instance avec un RestClient et un cache personnalisés (injection de test).
   *
   * @param restClient client HTTP à utiliser
   * @param cache cache Caffeine à utiliser
   */
  public WikidataArtisteSource(
      final RestClient restClient, final Cache<String, EnrichissementArtiste> cache) {
    this(new ClientSparqlWikidata(restClient), cache);
  }

  private WikidataArtisteSource(
      final ClientSparqlWikidata client, final Cache<String, EnrichissementArtiste> cache) {
    this.client = client;
    this.cache = cache;
  }

  @Override
  public String nom() {
    return "wikidata";
  }

  @Override
  public Map<UUID, EnrichissementArtiste> enrichirLot(final List<ArtisteAEnrichir> artistes) {
    final Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();

    final List<ArtisteAEnrichir> connus =
        artistes.stream().filter(a -> a.wikidataId() != null).toList();
    final List<ArtisteAEnrichir> aDecouvrir =
        artistes.stream().filter(a -> a.wikidataId() == null).toList();

    for (int i = 0; i < connus.size(); i += TAILLE_LOT_MAX) {
      final List<ArtisteAEnrichir> lot =
          connus.subList(i, Math.min(i + TAILLE_LOT_MAX, connus.size()));
      resultats.putAll(enrichirLotConnu(lot));
    }

    for (final ArtisteAEnrichir artiste : aDecouvrir) {
      decouvrir(artiste).ifPresent(e -> resultats.put(artiste.id(), e));
    }

    return resultats;
  }

  // --- Mode batch (Q-ID connu) ---

  private Map<UUID, EnrichissementArtiste> enrichirLotConnu(final List<ArtisteAEnrichir> lot) {
    final Map<String, UUID> qidVersUuid = new HashMap<>();
    for (final ArtisteAEnrichir a : lot) {
      qidVersUuid.put(a.wikidataId(), a.id());
    }

    final String sparql =
        construireRequeteBatch(lot.stream().map(ArtisteAEnrichir::wikidataId).toList());
    final Map<String, Object> data = client.executer(sparql);
    if (data == null) {
      return Map.of();
    }

    final Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();
    for (final Map<String, Object> binding : ClientSparqlWikidata.extraireBindings(data)) {
      final String qid = extraireQid(binding);
      if (qid == null) {
        continue;
      }
      final UUID artisteId = qidVersUuid.get(qid);
      if (artisteId == null) {
        continue;
      }
      final EnrichissementArtiste enrichissement = construireEnrichissement(qid, binding);
      cache.put(qid, enrichissement);
      resultats.put(artisteId, enrichissement);
    }
    return resultats;
  }

  private static String construireRequeteBatch(final List<String> qIds) {
    final String values =
        qIds.stream()
            .filter(ClientSparqlWikidata::estQIdValide)
            .map(q -> "wd:" + q)
            .collect(Collectors.joining(" "));
    // Les alias (skos:altLabel) sont agrégés dans une sous-requête groupée par ?item avant la
    // jointure avec deezerId/tmId et le SERVICE label — les grouper dans la requête principale
    // forcerait un GROUP BY sur toutes les colonnes, y compris ?itemLabel produit procéduralement
    // par le label service, ce qui n'est pas supporté proprement en SPARQL 1.1.
    return """
    SELECT ?item ?itemLabel ?deezerId ?tmId ?aliases WHERE {
      {
        SELECT ?item (GROUP_CONCAT(DISTINCT ?altLabel; separator="|") AS ?aliases) WHERE {
          VALUES ?item { %s }
          OPTIONAL { ?item skos:altLabel ?altLabel . FILTER(LANG(?altLabel) IN ("fr", "en")) }
        } GROUP BY ?item
      }
      OPTIONAL { ?item wdt:P2722 ?deezerId . }
      OPTIONAL { ?item wdt:P6262 ?tmId . }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    """
        .formatted(values);
  }

  // --- Mode découverte (Q-ID inconnu) ---

  /**
   * Résout un candidat par découverte mwapi, en refusant de deviner en cas d'homonymie
   * groupe/personne — ex. « Van Halen », où le groupe porte le vrai nom de famille de son
   * fondateur, guitariste et chanteur du groupe éponyme. Structurellement, {@code ?typeMatch} ne
   * peut valoir {@code "humain"} et {@code "groupe"} pour le même {@code ?item} ({@code wdt:P31
   * wd:Q5} exige une personne physique, incompatible avec l'appartenance à {@code wd:Q215380}) :
   * toute diversité de {@code ?typeMatch} parmi les {@value #TAILLE_CANDIDATS_DECOUVERTE}
   * meilleurs candidats mwapi signale donc deux entités distinctes en concurrence, jamais un faux
   * positif sur une même entité aux occupations multiples.
   */
  private Optional<EnrichissementArtiste> decouvrir(final ArtisteAEnrichir artiste) {
    final String sparql = construireRequeteDecouverte(artiste.nom());
    final Map<String, Object> data = client.executer(sparql);
    if (data == null) {
      return Optional.empty();
    }

    final List<Map<String, Object>> bindings = ClientSparqlWikidata.extraireBindings(data);
    if (bindings.isEmpty()) {
      LOG.info("Wikidata découverte — aucun candidat nom={}", artiste.nom());
      return Optional.empty();
    }

    final Set<String> typesDistincts =
        bindings.stream()
            .map(b -> ClientSparqlWikidata.valeurBinding(b, "typeMatch"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (typesDistincts.size() > 1) {
      LOG.warn(
          "Wikidata découverte — candidats ambigus (types {}) nom={} — non rapproché",
          typesDistincts,
          artiste.nom());
      ambiguites.add(artiste.id());
      return Optional.empty();
    }

    final String qid = extraireQid(bindings.get(0));
    if (qid == null) {
      return Optional.empty();
    }
    final EnrichissementArtiste enrichissement = construireEnrichissement(qid, bindings.get(0));
    cache.put(qid, enrichissement);
    return Optional.of(enrichissement);
  }

  private static final int TAILLE_CANDIDATS_DECOUVERTE = 5;

  /**
   * Filtre de découverte par nom : humain de profession musicien, humoriste ou auteur comique (un
   * humoriste, ex. Pierre-Emmanuel Barré, n'entrait dans aucune des deux branches d'origine; — sa
   * fiche réelle porte en fait {@code Q12406482} « auteur comique », dont la fermeture transitive P279*
   * ne remonte jamais vers {@code Q245068}, un troisième branchement est donc nécessaire plutôt qu'une
   * correction du premier), ou groupe musical. Les trois occupations humaines partagent le même
   * {@code ?typeMatch} (« humain ») car elles exigent toutes {@code wdt:P31 wd:Q5} — seule la
   * branche « groupe » (organisation, jamais une personne physique) peut produire un {@code
   * ?typeMatch} distinct pour la même recherche, cf. {@link #decouvrir}.
   */
  private static String construireRequeteDecouverte(final String nom) {
    return """
    SELECT ?item ?itemLabel ?deezerId ?tmId ?num ?typeMatch WHERE {
      SERVICE wikibase:mwapi {
        bd:serviceParam wikibase:api "EntitySearch".
        bd:serviceParam wikibase:endpoint "www.wikidata.org".
        bd:serviceParam mwapi:search "%s".
        bd:serviceParam mwapi:language "fr".
        ?item wikibase:apiOutputItem mwapi:item.
        ?num wikibase:apiOrdinal true.
      }
      {
        ?item wdt:P31 wd:Q5 .
        { ?item wdt:P106/wdt:P279* wd:Q639669 . }
        UNION { ?item wdt:P106/wdt:P279* wd:Q245068 . }
        UNION { ?item wdt:P106/wdt:P279* wd:Q12406482 . }
        BIND("humain" AS ?typeMatch)
      } UNION {
        ?item wdt:P31/wdt:P279* wd:Q215380 .
        BIND("groupe" AS ?typeMatch)
      }
      OPTIONAL { ?item wdt:P2722 ?deezerId . }
      OPTIONAL { ?item wdt:P6262 ?tmId . }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    ORDER BY ?num
    LIMIT %d
    """
        .formatted(ClientSparqlWikidata.echapperLitteral(nom), TAILLE_CANDIDATS_DECOUVERTE);
  }

  // --- Commun ---

  private static String extraireQid(final Map<String, Object> binding) {
    final String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
    return itemUri != null ? itemUri.substring(itemUri.lastIndexOf('/') + 1) : null;
  }

  private static EnrichissementArtiste construireEnrichissement(
      final String qid, final Map<String, Object> binding) {
    return new EnrichissementArtiste(
        qid,
        ClientSparqlWikidata.valeurBinding(binding, "itemLabel"),
        ClientSparqlWikidata.valeurBinding(binding, "deezerId"),
        ClientSparqlWikidata.valeurBinding(binding, "tmId"),
        extraireAlias(binding));
  }

  // GROUP_CONCAT (séparateur "|") produit une chaîne vide, jamais absente, quand aucun alias n'est
  // connu — filtrée explicitement plutôt que de produire une liste avec un unique élément vide.
  private static List<String> extraireAlias(final Map<String, Object> binding) {
    final String aliases = ClientSparqlWikidata.valeurBinding(binding, "aliases");
    if (aliases == null || aliases.isBlank()) {
      return List.of();
    }
    return Arrays.stream(aliases.split("\\|")).filter(a -> !a.isBlank()).toList();
  }
}
