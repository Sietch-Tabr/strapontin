/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.http.IdentiteHttp;
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
 * Client SPARQL Wikidata pour l'enrichissement des artistes — deux
 * modes selon que le Q-ID est connu (batch classique vs découverte par
 * nom, non validée contre l'attractionId TM existant) : détail :
 * CLAUDE.md, Enrichissement Wikidata/OSM.
 * @since 2.6.0
 * @author Kwaite
 */
public class WikidataArtisteSource
    implements SourceEnrichissementArtiste, SourceAvecBilanFetch, SourceAvecAmbiguitesDecouverte {

  private static final Logger LOG = LoggerFactory.getLogger(WikidataArtisteSource.class);

  // Rempli par decouvrir() sur ambiguïté humain/groupe, vidé par
  // preleverAmbiguites() — même patron "prélever" que preleverBilan
  // (BilanFetch), pas de dépendance JDBC dans ce module.
  private final Set<UUID> ambiguites = new HashSet<>();

  private static final int TAILLE_LOT_MAX = 50;
  private static final int TAILLE_CANDIDATS_DECOUVERTE = 5;
  // Au-delà d'un seul type distinct parmi les candidats, l'ambiguïté ne peut
  // plus être tranchée.
  private static final int NB_TYPES_DISTINCTS_NON_AMBIGU = 1;

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementArtiste> cache;

  @Override
  public Map<UUID, Map<String, Object>> preleverAmbiguites() {
    final Map<UUID, Map<String, Object>> resultat = new HashMap<>();
    ambiguites.forEach(id -> resultat.put(id, Map.of()));
    ambiguites.clear();
    return resultat;
  }

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

  /**
   * Crée une instance avec un client SPARQL par défaut et un cache 24h.
   *
   * @param identite identité déclarée à Wikidata
   */
  public WikidataArtisteSource(final IdentiteHttp identite) {
    this(
        new ClientSparqlWikidata(identite),
        Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).maximumSize(10_000).build());
  }

  /**
   * Crée une instance avec un RestClient et un cache personnalisés (injection
   * de test).
   *
   * @param restClient client HTTP à utiliser
   * @param cache cache Caffeine à utiliser
   * @param identite identité déclarée à Wikidata
   */
  public WikidataArtisteSource(
      final RestClient restClient,
      final Cache<String, EnrichissementArtiste> cache,
      final IdentiteHttp identite) {
    this(new ClientSparqlWikidata(restClient, identite), cache);
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
      final EnrichissementArtiste enrichissement =
          construireEnrichissement(
              qid,
              binding,
              identifiantUnique(ClientSparqlWikidata.valeurBinding(binding, "deezerId")),
              identifiantUnique(ClientSparqlWikidata.valeurBinding(binding, "tmId")));
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
    // Un seul GROUP BY, libellés lus directement : la sous-requête groupée
    // jointe au SERVICE label dépassait le délai de Wikidata (60 s) ou
    // renvoyait des alias vides, et aucun alias n'était jamais enregistré.
    return """
    SELECT ?item (SAMPLE(COALESCE(?libelleFr, ?libelleEn)) AS ?itemLabel)
           (GROUP_CONCAT(DISTINCT ?deezer; separator="|") AS ?deezerId)
           (GROUP_CONCAT(DISTINCT ?tm; separator="|") AS ?tmId)
           (GROUP_CONCAT(DISTINCT ?altLabel; separator="|") AS ?aliases) WHERE {
      VALUES ?item { %s }
      OPTIONAL { ?item rdfs:label ?libelleFr . FILTER(LANG(?libelleFr) = "fr") }
      OPTIONAL { ?item rdfs:label ?libelleEn . FILTER(LANG(?libelleEn) = "en") }
      OPTIONAL { ?item wdt:P2722 ?deezer . }
      OPTIONAL { ?item wdt:P7354 ?tm . }
      OPTIONAL { ?item skos:altLabel ?altLabel . FILTER(LANG(?altLabel) IN ("fr", "en")) }
    } GROUP BY ?item
    """
        .formatted(values);
  }

  // --- Mode découverte (Q-ID inconnu) ---

  /**
   * Résout un candidat par découverte mwapi, refuse de deviner sur
   * homonymie groupe/personne (Van Halen — détail : CLAUDE.md,
   * Enrichissement Wikidata/OSM) : {@code ?typeMatch} ne peut valoir
   * humain et groupe pour le même item, toute diversité signale deux
   * entités distinctes en concurrence.
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
    if (typesDistincts.size() > NB_TYPES_DISTINCTS_NON_AMBIGU) {
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
    final EnrichissementArtiste enrichissement =
        construireEnrichissement(
            qid,
            bindings.get(0),
            identifiantUnique(valeursDistinctes(bindings, qid, "deezerId")),
            identifiantUnique(valeursDistinctes(bindings, qid, "tmId")));
    cache.put(qid, enrichissement);
    return Optional.of(enrichissement);
  }

  /**
   * Filtre de découverte par nom : humain (musicien, humoriste/auteur
   * comique — trois branches SPARQL distinctes, détail : CLAUDE.md,
   * Enrichissement Wikidata/OSM), ou groupe musical.
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
      OPTIONAL { ?item wdt:P7354 ?tmId . }
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
      final String qid,
      final Map<String, Object> binding,
      final String deezerId,
      final String tmId) {
    return new EnrichissementArtiste(
        qid,
        ClientSparqlWikidata.valeurBinding(binding, "itemLabel"),
        deezerId,
        tmId,
        extraireAlias(binding));
  }

  // Plusieurs identifiants pour un élément (Genesis chez Deezer) : aucun n'est
  // choisi au hasard, la résolution par nom prend le relais.
  private static String identifiantUnique(final String valeurs) {
    if (valeurs == null || valeurs.isBlank() || valeurs.contains("|")) {
      return null;
    }
    return valeurs;
  }

  // Valeurs d'une colonne pour un élément, séparées par « | ».
  private static String valeursDistinctes(
      final List<Map<String, Object>> bindings, final String qid, final String cle) {
    return bindings.stream()
        .filter(b -> qid.equals(extraireQid(b)))
        .map(b -> ClientSparqlWikidata.valeurBinding(b, cle))
        .filter(v -> v != null && !v.isBlank())
        .distinct()
        .collect(Collectors.joining("|"));
  }

  // GROUP_CONCAT (séparateur "|") produit une chaîne vide, jamais absente,
  // quand aucun alias n'est connu — filtrée explicitement plutôt que de
  // produire une liste avec un unique élément vide.
  private static List<String> extraireAlias(final Map<String, Object> binding) {
    final String aliases = ClientSparqlWikidata.valeurBinding(binding, "aliases");
    if (aliases == null || aliases.isBlank()) {
      return List.of();
    }
    // Le même libellé en français et en anglais revient deux fois.
    return Arrays.stream(aliases.split("\\|")).filter(a -> !a.isBlank()).distinct().toList();
  }
}
