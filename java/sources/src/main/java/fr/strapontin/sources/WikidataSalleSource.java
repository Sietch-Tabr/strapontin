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
 * Client SPARQL Wikidata pour l'enrichissement des salles (étape 2) :
 * requête batch si Q-ID connu, sinon découverte par nom déléguée à {@link
 * WikidataSalleDecouverte} (détails wiki Sources-de-données).
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

  @SuppressWarnings("unchecked")
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
   * Revalide le type Wikidata (P31) des wikidataId déjà stockés contre
   * {@link WikidataSalleDecouverte#TYPES_EXCLUS} — un garde-fou introduit
   * après coup ne s'applique jamais rétroactivement aux ids déjà posés.
   * @param wikidataIds Q-IDs déjà stockés à revalider
   * @return le sous-ensemble dont le type Wikidata est désormais exclu
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
