/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(WikidataSalleSource.class);

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
  private final Cache<String, EnrichissementSalle> cache;

  /** Crée une instance avec un client SPARQL par défaut et un cache 24h. */
  public WikidataSalleSource() {
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
  public WikidataSalleSource(
      final RestClient restClient, final Cache<String, EnrichissementSalle> cache) {
    this(new ClientSparqlWikidata(restClient), cache);
  }

  private WikidataSalleSource(
      final ClientSparqlWikidata client, final Cache<String, EnrichissementSalle> cache) {
    this.client = client;
    this.cache = cache;
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
      decouvrir(salle).ifPresent(e -> resultats.put(salle.id(), e));
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
          parserCoordonnees(ClientSparqlWikidata.valeurBinding(binding, "coord"));
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
   * #TYPES_EXCLUS} — un garde-fou de type introduit après coup (ex. bar/restaurant, #1084) ne
   * s'applique qu'aux nouvelles découvertes, jamais rétroactivement aux {@code wikidata_id} posés
   * avant son existence.
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
        TYPES_EXCLUS.stream().map(qid -> "wd:" + qid).collect(Collectors.joining(" "));
    return """
    SELECT DISTINCT ?item WHERE {
      VALUES ?item { %s }
      ?item wdt:P31 ?type .
      VALUES ?type { %s }
    }
    """
        .formatted(items, typesExclusSparql);
  }

  // Wikidata renvoie P625 au format WKT "Point(lon lat)" (ordre longitude puis latitude, jamais
  // l'inverse — convention géographique standard).
  private static final Pattern COORDONNEES_WKT =
      Pattern.compile("Point\\(([-\\d.]+)\\s+([-\\d.]+)\\)");

  /**
   * Parse une valeur WKT Wikidata en {@code [longitude, latitude]}.
   *
   * @param wkt valeur brute du binding {@code ?coord}, ou {@code null}
   * @return {@code [longitude, latitude]}, ou {@code null} si absent/mal formé
   */
  private static double[] parserCoordonnees(final String wkt) {
    if (wkt == null) {
      return null;
    }
    final Matcher matcher = COORDONNEES_WKT.matcher(wkt);
    if (!matcher.find()) {
      return null;
    }
    try {
      return new double[] {
        Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))
      };
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // --- Mode découverte (Q-ID inconnu) ---

  /**
   * Sépare le premier séparateur significatif de sous-salle/qualificatif ({@code -}, {@code /},
   * {@code (}) d'un nom de salle, le cas échéant.
   */
  private static final Pattern SEPARATEUR_SOUS_SALLE = Pattern.compile("[-/(]");

  private static final Pattern PONCTUATION_FINALE = Pattern.compile("[\\p{Punct}\\s]+$");

  private Optional<EnrichissementSalle> decouvrir(final SalleAEnrichir salle) {
    Optional<EnrichissementSalle> resultat = decouvrirParNom(salle, salle.nom());
    if (resultat.isPresent()) {
      return resultat;
    }

    resultat = decouvrirNomTronqueParSeparateur(salle);
    if (resultat.isPresent()) {
      return resultat;
    }

    return decouvrirSansDernierMot(salle);
  }

  // la recherche mwapi:search échoue quasi systématiquement dès que le nom Strapontin porte un
  // suffixe de sous-salle/qualificatif ("Zenith Toulouse Metropole" → 0 résultat, "Zenith de
  // Toulouse" trouvé immédiatement), alors que l'entité existe bien sur Wikidata — l'échec se
  // produit dans l'appel mwapi, avant même la comparaison des candidats. La validation du candidat
  // reste faite contre salle.nomNorm() (nom complet), inchangée — seule la requête de recherche
  // envoyée à Wikidata utilise le nom tronqué.
  private Optional<EnrichissementSalle> decouvrirNomTronqueParSeparateur(
      final SalleAEnrichir salle) {
    final Matcher matcher = SEPARATEUR_SOUS_SALLE.matcher(salle.nom());
    if (!matcher.find() || matcher.start() == 0) {
      return Optional.empty();
    }
    final String nomTronque = salle.nom().substring(0, matcher.start()).trim();
    if (nomTronque.isEmpty()) {
      return Optional.empty();
    }

    log.info(
        "Wikidata découverte salle — repli sur nom tronqué nom={} tronque={}",
        salle.nom(),
        nomTronque);
    return decouvrirParNom(salle, nomTronque);
  }

  // Convention de nommage fréquente côté sources (Ticketmaster/Eventim/Digitick) : le nom de la
  // salle est suffixé de sa ville sans séparateur ("Salle Poirel Nancy", "La Belle Electrique
  // Grenoble") — vérifié en direct contre l'API Wikidata, la recherche mwapi échoue sur le nom
  // complet mais trouve l'entité sur le seul nom de salle une fois la ville retirée. Un seul mot
  // est retiré, jamais une troncature récursive : au-delà, le risque de réduire le terme de
  // recherche à un mot générique non discriminant l'emporte sur le gain (une ville à qualificatif
  // composé, ex. "Vitry Sur Seine", n'est pas couverte par ce repli). La validation finale reste
  // faite contre le nom complet ({@link #decouvrirParNom}), ce repli n'élargit que l'ensemble de
  // candidats interrogés, jamais le critère d'acceptation.
  private Optional<EnrichissementSalle> decouvrirSansDernierMot(final SalleAEnrichir salle) {
    final String nom = salle.nom().trim();
    final int dernierEspace = nom.lastIndexOf(' ');
    if (dernierEspace <= 0) {
      return Optional.empty();
    }
    // trim() ne retire que les espaces — un séparateur ("-", "/") laissé en bout de chaîne
    // produirait une recherche mwapi inutilement sale (ex. "Zenith Toulouse -")
    final String nomTronque =
        PONCTUATION_FINALE.matcher(nom.substring(0, dernierEspace)).replaceAll("").trim();
    // au moins deux mots restants — un seul mot est trop générique pour être discriminant
    if (!nomTronque.contains(" ")) {
      return Optional.empty();
    }

    log.info(
        "Wikidata découverte salle — repli sans dernier mot nom={} tronque={}",
        salle.nom(),
        nomTronque);
    return decouvrirParNom(salle, nomTronque);
  }

  private Optional<EnrichissementSalle> decouvrirParNom(
      final SalleAEnrichir salle, final String nomRecherche) {
    final String sparql = construireRequeteDecouverte(nomRecherche);
    final Map<String, Object> data = client.executer(sparql);
    if (data == null) {
      return Optional.empty();
    }

    for (final Map<String, Object> binding : ClientSparqlWikidata.extraireBindings(data)) {
      final String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
      if (itemUri == null) {
        continue;
      }
      final String itemLabel = ClientSparqlWikidata.valeurBinding(binding, "itemLabel");
      final String aliases = ClientSparqlWikidata.valeurBinding(binding, "aliases");
      final String communeLabel = ClientSparqlWikidata.valeurBinding(binding, "communeLabel");
      final String codePostal = ClientSparqlWikidata.valeurBinding(binding, "codePostal");
      final double[] coord =
          parserCoordonnees(ClientSparqlWikidata.valeurBinding(binding, "coord"));
      final boolean localisationConnue =
          (communeLabel != null && !communeLabel.isBlank())
              || (codePostal != null && !codePostal.isBlank());
      final String labelCorrespondant =
          trouverLabelCorrespondant(salle.nomNorm(), itemLabel, aliases, localisationConnue);
      if (labelCorrespondant == null) {
        continue;
      }

      if (villeIncompatible(salle.ville(), communeLabel)) {
        log.info(
            "Wikidata découverte salle — rejet commune incompatible nom={} villeSalle={}"
                + " communeCandidat={}",
            nomRecherche,
            salle.ville(),
            communeLabel);
        continue;
      }
      if (deptIncompatible(salle.dept(), codePostal)) {
        log.info(
            "Wikidata découverte salle — rejet département incompatible nom={} deptSalle={}"
                + " codePostalCandidat={}",
            nomRecherche,
            salle.dept(),
            codePostal);
        continue;
      }

      final String qid = itemUri.substring(itemUri.lastIndexOf('/') + 1);
      final EnrichissementSalle enrichissement =
          new EnrichissementSalle(
              qid,
              labelCorrespondant,
              communeLabel,
              null,
              null,
              coord != null ? coord[1] : null,
              coord != null ? coord[0] : null);
      cache.put(qid, enrichissement);
      return Optional.of(enrichissement);
    }
    log.info("Wikidata découverte salle — aucun candidat exact nom={}", nomRecherche);
    return Optional.empty();
  }

  /**
   * Vérifie que la commune du candidat Wikidata ne contredit pas la ville connue de la salle
   * Strapontin.
   *
   * <p>Constaté en production : le repli sur nom tronqué peut retenir en rang 0 un candidat au nom
   * générique partagé par plusieurs communes (ex. "Collegiale Saint" → "collégiale Saint-Agricol
   * d'Avignon", alors que la salle recherchée est à Chinon) — aucune des désambiguïsations de
   * {@link #trouverLabelCorrespondant} ne porte sur la localisation. Ce garde-fou n'écarte que les
   * cas où les deux villes sont connues et diffèrent ; une ville inconnue d'un côté ou de l'autre
   * ne bloque rien (reste au niveau de risque actuel, pas de régression sur les cas déjà couverts).
   *
   * @param villeSalle ville connue de la salle Strapontin ({@link SalleAEnrichir#ville}), peut être
   *     {@code null}
   * @param communeLabel commune du candidat Wikidata (P131), peut être {@code null}
   * @return {@code true} si les deux villes sont connues et ne correspondent pas
   */
  private static boolean villeIncompatible(final String villeSalle, final String communeLabel) {
    if (villeSalle == null || villeSalle.isBlank()) {
      return false;
    }
    if (communeLabel == null || communeLabel.isBlank()) {
      return false;
    }
    return !Normalisation.normaliserVille(villeSalle)
        .equals(Normalisation.normaliserVille(communeLabel));
  }

  /**
   * Vérifie que le département du candidat Wikidata (dérivé du code postal de sa commune) ne
   * contredit pas le département connu de la salle Strapontin.
   *
   * <p>Complète {@link #villeIncompatible} : {@code salles.dept} est renseigné dès la création de
   * la salle à partir d'un concert réel (fiable), alors que {@code salles.ville} peut rester vide
   * ou fausse au moment de cet enrichissement (angle mort constaté en production sur plusieurs
   * salles rattachées à tort à une entité Wikidata sans rapport — gare, arrêt de tram, bâtiment
   * homonyme — faute de ville connue pour bloquer le rapprochement). Même politique que {@link
   * #villeIncompatible} : ne rejette que si les deux départements sont connus et diffèrent.
   *
   * @param deptSalle département connu de la salle Strapontin ({@link SalleAEnrichir#dept}), peut
   *     être {@code null}
   * @param codePostal code postal de la commune du candidat Wikidata (P281), peut être {@code null}
   * @return {@code true} si les deux départements sont connus et ne correspondent pas
   */
  private static boolean deptIncompatible(final String deptSalle, final String codePostal) {
    if (deptSalle == null || deptSalle.isBlank()) {
      return false;
    }
    final String deptCandidat = Normalisation.extraireDeptFrancais(codePostal);
    if (deptCandidat == null) {
      return false;
    }
    return !deptSalle.equals(deptCandidat);
  }

  // Nombre minimal de tokens significatifs cibles pour appliquer le containment asymétrique
  // évite qu'un nom d'un seul mot ("galaxie") soit considéré contenu dans n'importe
  // quel candidat plus long au seul motif qu'il partage ce mot.
  private static final int TAILLE_MIN_TOKENS_CONTAINMENT = 2;

  /**
   * Cherche une correspondance floue ({@link CorrespondanceSalle#estDoublonSalle}, complété par un
   * containment de tokens asymétrique, cf. {@link #estCorrespondanceAsymetrique}) sur le libellé
   * principal Wikidata ou l'un de ses alias.
   *
   * <p>Les alias sont vérifiés en plus du libellé principal, pas à la place : les grandes salles
   * changent régulièrement de nom commercial (sponsoring) et Wikidata conserve souvent l'ancien nom
   * comme libellé principal, le nom actuel n'étant qu'un alias.
   *
   * @param nomNormCible nom de la salle Strapontin, normalisé ({@link SalleAEnrichir#nomNorm})
   * @param itemLabel libellé principal du candidat Wikidata (peut être {@code null})
   * @param aliases alias du candidat séparés par {@code "|"} (peut être {@code null} ou vide)
   * @param localisationConnue {@code false} si le candidat n'a ni commune ni code postal Wikidata
   *     connus — désactive alors le critère pivot d'{@link CorrespondanceSalle}, sans aucun signal
   *     indépendant pour confirmer un candidat validé sur ce seul critère (voir {@link
   *     CorrespondanceSalle#estDoublonSalle(String, String, boolean)})
   * @return le libellé (principal ou alias) qui correspond, ou {@code null} si aucun
   */
  private static String trouverLabelCorrespondant(
      final String nomNormCible,
      final String itemLabel,
      final String aliases,
      final boolean localisationConnue) {
    if (itemLabel != null
        && estCorrespondanceAsymetrique(
            nomNormCible, Normalisation.normaliserTexte(itemLabel), localisationConnue)) {
      return itemLabel;
    }
    if (aliases == null || aliases.isBlank()) {
      return null;
    }
    for (final String alias : aliases.split("\\|")) {
      if (estCorrespondanceAsymetrique(
          nomNormCible, Normalisation.normaliserTexte(alias), localisationConnue)) {
        return alias;
      }
    }
    return null;
  }

  /**
   * Complète {@link CorrespondanceSalle#estDoublonSalle} par un containment de tokens asymétrique :
   * couvre le cas où Wikidata combine l'ancien et le nouveau nom dans un seul alias (constaté en
   * production : « Dôme de Paris » — nom Strapontin actuel — n'est rapproché d'aucun candidat mwapi
   * malgré l'existence de l'entité correcte en rang 0, son libellé principal étant « Palais des
   * sports » et son alias le plus proche « Dôme de Paris - Palais des Sports »). Cet alias est trop
   * long relativement à notre nom pour satisfaire le containment de caractères (ratio {@code <
   * 0.5}) ou le Jaccard symétrique (0.4, sous le seuil 0.5) de {@code estDoublonSalle} — les deux
   * critères pénalisent le nombre de mots supplémentaires côté Wikidata, alors que ces mots
   * supplémentaires sont justement l'ancien nom, pas du bruit.
   *
   * <p>Asymétrique à dessein : exige que <strong>tous</strong> les tokens significatifs de notre
   * nom (le plus souvent court, le nom commercial actuel) soient présents dans le candidat Wikidata
   * (souvent plus long, combinant plusieurs noms), jamais l'inverse — un candidat plus court que
   * notre nom reste filtré par {@code estDoublonSalle} seul. Le {@value
   * #TAILLE_MIN_TOKENS_CONTAINMENT} minimum de tokens cible évite qu'un nom d'un seul mot
   * corresponde à n'importe quel candidat le contenant (même risque de faux positif qu'un Jaccard
   * non borné, documenté dans {@link CorrespondanceSalle}).
   *
   * @param nomNormCible nom Strapontin normalisé
   * @param candidatNorm libellé ou alias Wikidata normalisé
   * @param localisationConnue transmis tel quel à {@link
   *     CorrespondanceSalle#estDoublonSalle(String, String, boolean)}
   * @return {@code true} si les tokens significatifs de {@code nomNormCible} sont tous inclus dans
   *     ceux de {@code candidatNorm}
   */
  private static boolean estCorrespondanceAsymetrique(
      final String nomNormCible, final String candidatNorm, final boolean localisationConnue) {
    if (CorrespondanceSalle.estDoublonSalle(nomNormCible, candidatNorm, localisationConnue)) {
      return true;
    }
    final Set<String> tokensCible = CorrespondanceSalle.tokensSignificatifs(nomNormCible);
    if (tokensCible.size() < TAILLE_MIN_TOKENS_CONTAINMENT) {
      return false;
    }
    final Set<String> tokensCandidat = CorrespondanceSalle.tokensSignificatifs(candidatNorm);
    return tokensCandidat.containsAll(tokensCible);
  }

  // Types Wikidata (P31) structurellement incompatibles avec un lieu de spectacle : gare
  // (Q3455524), arrêt de tram (Q6465), commune française (Q484170), gare ferroviaire (Q55488),
  // arrêt de transport en commun (Q2175765), bar (Q187456), restaurant (Q11707), quotidien
  // régional (Q1002697), journal (Q11032) — un candidat de l'un de ces types est nécessairement
  // une fausse correspondance, quel que soit son score de similarité de nom. Exposée pour permettre
  // à {@code CoherenceWikidataSalleService} de revalider les {@code wikidata_id} déjà stockés
  // contre ces mêmes types (garde-fou introduit après coup ne s'appliquant pas rétroactivement aux
  // découvertes antérieures).
  public static final Set<String> TYPES_EXCLUS =
      Set.of(
          "Q3455524",
          "Q6465",
          "Q484170",
          "Q55488",
          "Q2175765",
          "Q187456",
          "Q11707",
          "Q1002697",
          "Q11032");

  private static String construireRequeteDecouverte(final String nom) {
    final String typesExclusSparql =
        TYPES_EXCLUS.stream().map(qid -> "wd:" + qid).collect(Collectors.joining(" "));
    return """
    SELECT ?item ?itemLabel ?communeLabel ?num
      (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (SAMPLE(?cp) AS ?codePostal)
      (SAMPLE(?coordValue) AS ?coord)
    WHERE {
      SERVICE wikibase:mwapi {
        bd:serviceParam wikibase:api "EntitySearch".
        bd:serviceParam wikibase:endpoint "www.wikidata.org".
        bd:serviceParam mwapi:search "%s".
        bd:serviceParam mwapi:language "fr".
        ?item wikibase:apiOutputItem mwapi:item.
        ?num wikibase:apiOrdinal true.
      }
      { ?item wdt:P625 [] . } UNION { ?item wdt:P31 wd:Q1060829 . }
      ?item wdt:P17 wd:Q142 .
      FILTER NOT EXISTS {
        ?item wdt:P31 ?typeExclu .
        VALUES ?typeExclu { %s }
      }
      OPTIONAL { ?item skos:altLabel ?alias . FILTER(LANG(?alias) = "fr") }
      OPTIONAL { ?item wdt:P625 ?coordValue }
      OPTIONAL {
        ?item wdt:P131 ?commune .
        ?commune rdfs:label ?communeLabel .
        FILTER(LANG(?communeLabel) = "fr")
        OPTIONAL { ?commune wdt:P281 ?cp }
      }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    GROUP BY ?item ?itemLabel ?communeLabel ?num
    ORDER BY ?num
    LIMIT 5
    """
        .formatted(ClientSparqlWikidata.echapperLitteral(nom), typesExclusSparql);
  }
}
