/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/// Client SPARQL Wikidata pour l'enrichissement des artistes (#711 étape 3 + #716).
///
/// Deux modes selon que le Q-ID est déjà connu :
///
/// <ul>
///   <li>{@code wikidataId != null} : requête batch classique (clause {@code VALUES}, ≤50)
///   <li>{@code wikidataId == null} : découverte par recherche de nom via
///       {@code SERVICE wikibase:mwapi} (une requête par artiste, non batchable), filtrée par les
///       types intrinsèques Wikidata (humain+musicien ou groupe) — <strong>jamais validée contre
///       l'attractionId Ticketmaster existant</strong> : Wikidata fait autorité pour l'identité de
///       l'artiste, indépendamment de TM (décision actée, cf. plan du jalon 2.6.0). Le P6262
///       éventuellement porté par le Q-ID trouvé sert ensuite à corriger notre lien vers TM
///       (#716), jamais l'inverse.
/// </ul>
///
/// @since 2.6.0
/// @author Kwaite
public class WikidataArtisteSource implements SourceEnrichissementArtiste {

  private static final Logger log = LoggerFactory.getLogger(WikidataArtisteSource.class);

  private static final int TAILLE_LOT_MAX = 50;
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 500;

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementArtiste> cache;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// Crée une instance avec un client SPARQL par défaut et un cache 24h.
  public WikidataArtisteSource() {
    this(
        new ClientSparqlWikidata(),
        Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).maximumSize(10_000).build());
  }

  /// Crée une instance avec un RestClient et un cache personnalisés (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  /// @param cache      cache Caffeine à utiliser
  public WikidataArtisteSource(RestClient restClient, Cache<String, EnrichissementArtiste> cache) {
    this(new ClientSparqlWikidata(restClient), cache);
  }

  private WikidataArtisteSource(
      ClientSparqlWikidata client, Cache<String, EnrichissementArtiste> cache) {
    this.client = client;
    this.cache = cache;
  }

  @Override
  public String nom() {
    return "wikidata";
  }

  @Override
  public Map<UUID, EnrichissementArtiste> enrichirLot(List<ArtisteAEnrichir> artistes) {
    Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();

    List<ArtisteAEnrichir> connus = artistes.stream().filter(a -> a.wikidataId() != null).toList();
    List<ArtisteAEnrichir> aDecouvrir =
        artistes.stream().filter(a -> a.wikidataId() == null).toList();

    for (int i = 0; i < connus.size(); i += TAILLE_LOT_MAX) {
      List<ArtisteAEnrichir> lot = connus.subList(i, Math.min(i + TAILLE_LOT_MAX, connus.size()));
      resultats.putAll(enrichirLotConnu(lot));
    }

    for (ArtisteAEnrichir artiste : aDecouvrir) {
      attendreRateLimitGlobal();
      decouvrir(artiste).ifPresent(e -> resultats.put(artiste.id(), e));
    }

    return resultats;
  }

  // --- Mode batch (Q-ID connu) ---

  private Map<UUID, EnrichissementArtiste> enrichirLotConnu(List<ArtisteAEnrichir> lot) {
    Map<String, UUID> qidVersUuid = new HashMap<>();
    for (ArtisteAEnrichir a : lot) qidVersUuid.put(a.wikidataId(), a.id());

    String sparql = construireRequeteBatch(lot.stream().map(ArtisteAEnrichir::wikidataId).toList());
    Map<String, Object> data = client.executer(sparql);
    if (data == null) return Map.of();

    Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();
    for (Map<String, Object> binding : extraireBindings(data)) {
      String qid = extraireQid(binding);
      if (qid == null) continue;
      UUID artisteId = qidVersUuid.get(qid);
      if (artisteId == null) continue;
      EnrichissementArtiste enrichissement = construireEnrichissement(qid, binding);
      cache.put(qid, enrichissement);
      resultats.put(artisteId, enrichissement);
    }
    return resultats;
  }

  private static String construireRequeteBatch(List<String> qIds) {
    String values = qIds.stream().map(q -> "wd:" + q).collect(Collectors.joining(" "));
    return """
    SELECT ?item ?itemLabel ?deezerId ?tmId WHERE {
      VALUES ?item { %s }
      OPTIONAL { ?item wdt:P2722 ?deezerId . }
      OPTIONAL { ?item wdt:P6262 ?tmId . }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    """
        .formatted(values);
  }

  // --- Mode découverte (Q-ID inconnu) ---

  private Optional<EnrichissementArtiste> decouvrir(ArtisteAEnrichir artiste) {
    String sparql = construireRequeteDecouverte(artiste.nom());
    Map<String, Object> data = client.executer(sparql);
    if (data == null) return Optional.empty();

    List<Map<String, Object>> bindings = extraireBindings(data);
    if (bindings.isEmpty()) {
      log.info("Wikidata découverte — aucun candidat nom={}", artiste.nom());
      return Optional.empty();
    }
    String qid = extraireQid(bindings.get(0));
    if (qid == null) return Optional.empty();
    EnrichissementArtiste enrichissement = construireEnrichissement(qid, bindings.get(0));
    cache.put(qid, enrichissement);
    return Optional.of(enrichissement);
  }

  private static String construireRequeteDecouverte(String nom) {
    return """
    SELECT ?item ?itemLabel ?deezerId ?tmId ?num WHERE {
      SERVICE wikibase:mwapi {
        bd:serviceParam wikibase:api "EntitySearch".
        bd:serviceParam wikibase:endpoint "www.wikidata.org".
        bd:serviceParam mwapi:search "%s".
        bd:serviceParam mwapi:language "fr".
        ?item wikibase:apiOutputItem mwapi:item.
        ?num wikibase:apiOrdinal true.
      }
      {
        ?item wdt:P31 wd:Q5 ; wdt:P106/wdt:P279* wd:Q639669 .
      } UNION {
        ?item wdt:P31/wdt:P279* wd:Q215380 .
      }
      OPTIONAL { ?item wdt:P2722 ?deezerId . }
      OPTIONAL { ?item wdt:P6262 ?tmId . }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    ORDER BY ?num
    LIMIT 1
    """
        .formatted(nom.replace("\"", "\\\""));
  }

  // --- Commun ---

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> extraireBindings(Map<String, Object> data) {
    var results = (Map<String, Object>) data.get("results");
    if (results == null) return List.of();
    var bindings = (List<Map<String, Object>>) results.get("bindings");
    return bindings != null ? bindings : List.of();
  }

  private static String extraireQid(Map<String, Object> binding) {
    String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
    return itemUri != null ? itemUri.substring(itemUri.lastIndexOf('/') + 1) : null;
  }

  private static EnrichissementArtiste construireEnrichissement(
      String qid, Map<String, Object> binding) {
    return new EnrichissementArtiste(
        qid,
        ClientSparqlWikidata.valeurBinding(binding, "itemLabel"),
        ClientSparqlWikidata.valeurBinding(binding, "deezerId"),
        ClientSparqlWikidata.valeurBinding(binding, "tmId"));
  }

  private synchronized void attendreRateLimitGlobal() {
    long maintenant = System.currentTimeMillis();
    long prochainAppel = dernierAppelMs.get() + DELAI_MIN_ENTRE_APPELS_MS;
    if (maintenant < prochainAppel) {
      try {
        Thread.sleep(prochainAppel - maintenant);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }
}
