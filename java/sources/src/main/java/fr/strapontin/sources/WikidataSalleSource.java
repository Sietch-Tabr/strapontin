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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/// Client SPARQL Wikidata pour l'enrichissement des salles (étape 2).
///
/// Deux modes selon que le Q-ID est déjà connu :
///
/// <ul>
///   <li>{@code wikidataId != null} (fourni par {@link OverpassSalleSource} ou déjà stocké en
///       base) : requête batch classique (clause {@code VALUES}, ≤50)
///   <li>{@code wikidataId == null} : découverte par recherche de nom via
///       {@code SERVICE wikibase:mwapi} (une requête par salle, non batchable) — Overpass n'est
///       plus un passage obligé pour les salles (son instance publique traverse un incident de
///       surcharge documenté, voir {@link OverpassSalleSource}). <strong>Pas de filtre de type
///       positif</strong> (contrairement à {@link WikidataArtisteSource}) : l'ontologie Wikidata
///       des salles de spectacle est hétérogène (ex. « salle de spectacles polyvalente » Q1763828
///       est sous-classe P279 de « gymnase » Q14092), un filtre transitif risquerait de faire
///       correspondre des équipements sportifs. Un filtre négatif exclut en revanche les entités
///       de type région/département/commune (P31 région administrative, département français,
///       commune française) ainsi que les infrastructures de transport (gare, arrêt de tram) :
///       une recherche par nom peut sinon retourner une entité administrative ou de transport
///       homonyme plutôt que le lieu physique recherché — constaté en production sur une gare
///       (Villepinte) et un arrêt de tram (Bordeaux) retenus à tort. Le département de la commune
///       du candidat (déduit de son code postal, P281) est également confronté au département
///       connu de la salle Strapontin quand les deux sont disponibles (voir {@link
// #deptIncompatible}) —
///       {@code salles.ville} peut rester vide au moment de cet enrichissement quand {@code
///       salles.dept} est déjà fiable (renseigné dès la création à partir d'un concert réel). En
///       contrepartie, la requête exige {@code wdt:P625}
///       (coordonnées géographiques) : sans ce garde-fou, une recherche par nom seul peut retourner
///       en premier résultat une œuvre culturelle homonyme (constaté en production : pour
///       « L'Olympia », le premier candidat mwapi est un <em>album</em> d'Émilie Simon sorti en
///       2007, pas la salle) — un album/single/livre n'a pas de coordonnées, une salle en a
///       toujours une. La désambiguïsation finale réutilise {@link
// CorrespondanceSalle#estDoublonSalle}
///       (module {@code core}, même algorithme que {@code ReconciliateurSalles} côté worker :
///       Levenshtein ≥ 0.75, Jaccard tokens, critère pivot) plutôt qu'une simple égalité de chaîne
///       — nécessaire pour absorber à la fois l'article défini français (« L'Olympia » ↔ « Olympia
// »,
///       via containment) et les suffixes descriptifs fréquents côté Strapontin absents du libellé
///       Wikidata (« Zenith Paris - la Villette » ↔ « Zénith Paris », via Jaccard tokens). La
///       comparaison porte aussi sur les <strong>alias</strong> ({@code skos:altLabel}) : les
///       grandes salles changent régulièrement de nom commercial (sponsoring) et Wikidata
///       conserve souvent l'ancien nom comme libellé principal, le nom actuel n'étant qu'un alias
///       — constaté en production : « Accor Arena » (nom Strapontin, actuel) n'est qu'un alias de
///       l'entité dont le libellé principal Wikidata est resté « Palais Omnisports de
///       Paris-Bercy ». Sans vérifier les alias, ces salles ne seraient jamais enrichies malgré
///       une entrée Wikidata existante. Réutiliser l'algorithme flou du projet plutôt qu'une
///       correspondance exacte reste raisonnablement sûr ici : les candidats mwapi sont déjà
///       restreints aux résultats les plus pertinents pour le nom recherché, et les seuils
///       (Jaccard 0.5, pivot ≥7 chars) ont été calibrés dans le projet précisément pour rejeter
///       les homonymes inter-villes (« zenith bordeaux » vs « zenith caen »). <strong>Ce calibrage
///       ne couvre pas les homonymes internationaux</strong> — un nom propre français perd son
///       identité après suppression des accents et peut coïncider avec un nom anglais sans rapport
///       (constaté en production : « Orléans Arena » → « orleans arena », strictement identique à
///       « Orleans Arena » Q3356467, une salle de Las Vegas, Nevada — correspondance exacte
///       immédiate, aucun seuil flou impliqué). La requête exige donc {@code wdt:P17 wd:Q142}
///       (pays = France) : Strapontin ne suit que des concerts français (TM/Eventim/Digitick
///       France), toute salle hors de ce périmètre est nécessairement une fausse correspondance.
/// </ul>
///
/// @since 2.6.0
/// @author Kwaite
public class WikidataSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(WikidataSalleSource.class);

  /// Retourne le bilan agrégé des appels SPARQL Wikidata depuis le dernier prélèvement.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public fr.strapontin.core.BilanFetch preleverBilan() {
    return client.preleverBilan();
  }

  private static final int TAILLE_LOT_MAX = 50;

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementSalle> cache;

  /// Crée une instance avec un client SPARQL par défaut et un cache 24h.
  public WikidataSalleSource() {
    this(
        new ClientSparqlWikidata(),
        Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).maximumSize(10_000).build());
  }

  /// Crée une instance avec un RestClient et un cache personnalisés (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  /// @param cache cache Caffeine à utiliser
  public WikidataSalleSource(RestClient restClient, Cache<String, EnrichissementSalle> cache) {
    this(new ClientSparqlWikidata(restClient), cache);
  }

  private WikidataSalleSource(
      ClientSparqlWikidata client, Cache<String, EnrichissementSalle> cache) {
    this.client = client;
    this.cache = cache;
  }

  @Override
  public String nom() {
    return "wikidata";
  }

  @Override
  public Map<UUID, EnrichissementSalle> enrichirLot(List<SalleAEnrichir> salles) {
    Map<UUID, EnrichissementSalle> resultats = new HashMap<>();

    List<SalleAEnrichir> connues = salles.stream().filter(s -> s.wikidataId() != null).toList();
    List<SalleAEnrichir> aDecouvrir = salles.stream().filter(s -> s.wikidataId() == null).toList();

    List<SalleAEnrichir> nonCachees = new ArrayList<>();
    for (SalleAEnrichir s : connues) {
      EnrichissementSalle depuisCache = cache.getIfPresent(s.wikidataId());
      if (depuisCache != null) {
        resultats.put(s.id(), depuisCache);
      } else {
        nonCachees.add(s);
      }
    }

    for (int i = 0; i < nonCachees.size(); i += TAILLE_LOT_MAX) {
      List<SalleAEnrichir> lot =
          nonCachees.subList(i, Math.min(i + TAILLE_LOT_MAX, nonCachees.size()));
      resultats.putAll(enrichirLotConnu(lot));
    }

    for (SalleAEnrichir salle : aDecouvrir) {
      decouvrir(salle).ifPresent(e -> resultats.put(salle.id(), e));
    }

    return resultats;
  }

  // --- Mode batch (Q-ID connu) ---

  private Map<UUID, EnrichissementSalle> enrichirLotConnu(List<SalleAEnrichir> lot) {
    Map<String, UUID> qidVersUuid = new HashMap<>();
    for (SalleAEnrichir s : lot) qidVersUuid.put(s.wikidataId(), s.id());

    String sparql = construireRequeteSparql(lot.stream().map(SalleAEnrichir::wikidataId).toList());
    Map<String, Object> data = client.executer(sparql);
    if (data == null) return Map.of();
    return mapperReponse(data, qidVersUuid);
  }

  private static String construireRequeteSparql(List<String> qIds) {
    String values = qIds.stream().map(q -> "wd:" + q).collect(Collectors.joining(" "));
    return """
    SELECT ?item ?itemLabel ?communeLabel WHERE {
      VALUES ?item { %s }
      OPTIONAL {
        ?item wdt:P131 ?commune .
        ?commune rdfs:label ?communeLabel .
        FILTER(LANG(?communeLabel) = "fr")
      }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    """
        .formatted(values);
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, EnrichissementSalle> mapperReponse(
      Map<String, Object> data, Map<String, UUID> qidVersUuid) {
    Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    var results = (Map<String, Object>) data.get("results");
    if (results == null) return resultats;
    var bindings = (List<Map<String, Object>>) results.get("bindings");
    if (bindings == null) return resultats;

    for (var binding : bindings) {
      String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
      if (itemUri == null) continue;
      String qid = itemUri.substring(itemUri.lastIndexOf('/') + 1);
      UUID salleId = qidVersUuid.get(qid);
      if (salleId == null) continue;

      EnrichissementSalle enrichissement =
          new EnrichissementSalle(
              qid,
              ClientSparqlWikidata.valeurBinding(binding, "itemLabel"),
              ClientSparqlWikidata.valeurBinding(binding, "communeLabel"),
              null,
              null);
      resultats.put(salleId, enrichissement);
      cache.put(qid, enrichissement);
    }
    return resultats;
  }

  // --- Mode découverte (Q-ID inconnu) ---

  /// Sépare le premier séparateur significatif de sous-salle/qualificatif (`-`, `/`, `(`) d'un nom
  /// de salle, le cas échéant.
  private static final Pattern SEPARATEUR_SOUS_SALLE = Pattern.compile("[-/(]");

  private static final Pattern PONCTUATION_FINALE = Pattern.compile("[\\p{Punct}\\s]+$");

  private Optional<EnrichissementSalle> decouvrir(SalleAEnrichir salle) {
    Optional<EnrichissementSalle> resultat = decouvrirParNom(salle, salle.nom());
    if (resultat.isPresent()) return resultat;

    resultat = decouvrirNomTronqueParSeparateur(salle);
    if (resultat.isPresent()) return resultat;

    return decouvrirSansDernierMot(salle);
  }

  // la recherche mwapi:search échoue quasi systématiquement dès que le nom Strapontin porte un
  // suffixe de sous-salle/qualificatif ("Zenith Toulouse Metropole" → 0 résultat, "Zenith de
  // Toulouse" trouvé immédiatement), alors que l'entité existe bien sur Wikidata — l'échec se
  // produit dans l'appel mwapi, avant même la comparaison des candidats. La validation du candidat
  // reste faite contre salle.nomNorm() (nom complet), inchangée — seule la requête de recherche
  // envoyée à Wikidata utilise le nom tronqué.
  private Optional<EnrichissementSalle> decouvrirNomTronqueParSeparateur(SalleAEnrichir salle) {
    var matcher = SEPARATEUR_SOUS_SALLE.matcher(salle.nom());
    if (!matcher.find() || matcher.start() == 0) return Optional.empty();
    String nomTronque = salle.nom().substring(0, matcher.start()).trim();
    if (nomTronque.isEmpty()) return Optional.empty();

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
  private Optional<EnrichissementSalle> decouvrirSansDernierMot(SalleAEnrichir salle) {
    String nom = salle.nom().trim();
    int dernierEspace = nom.lastIndexOf(' ');
    if (dernierEspace <= 0) return Optional.empty();
    // trim() ne retire que les espaces — un séparateur ("-", "/") laissé en bout de chaîne
    // produirait une recherche mwapi inutilement sale (ex. "Zenith Toulouse -")
    String nomTronque =
        PONCTUATION_FINALE.matcher(nom.substring(0, dernierEspace)).replaceAll("").trim();
    // au moins deux mots restants — un seul mot est trop générique pour être discriminant
    if (!nomTronque.contains(" ")) return Optional.empty();

    log.info(
        "Wikidata découverte salle — repli sans dernier mot nom={} tronque={}",
        salle.nom(),
        nomTronque);
    return decouvrirParNom(salle, nomTronque);
  }

  private Optional<EnrichissementSalle> decouvrirParNom(SalleAEnrichir salle, String nomRecherche) {
    String sparql = construireRequeteDecouverte(nomRecherche);
    Map<String, Object> data = client.executer(sparql);
    if (data == null) return Optional.empty();

    for (var binding : extraireBindings(data)) {
      String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
      if (itemUri == null) continue;
      String itemLabel = ClientSparqlWikidata.valeurBinding(binding, "itemLabel");
      String aliases = ClientSparqlWikidata.valeurBinding(binding, "aliases");
      String labelCorrespondant = trouverLabelCorrespondant(salle.nomNorm(), itemLabel, aliases);
      if (labelCorrespondant == null) continue;

      String communeLabel = ClientSparqlWikidata.valeurBinding(binding, "communeLabel");
      if (villeIncompatible(salle.ville(), communeLabel)) {
        log.info(
            "Wikidata découverte salle — rejet commune incompatible nom={} villeSalle={}"
                + " communeCandidat={}",
            nomRecherche,
            salle.ville(),
            communeLabel);
        continue;
      }
      String codePostal = ClientSparqlWikidata.valeurBinding(binding, "codePostal");
      if (deptIncompatible(salle.dept(), codePostal)) {
        log.info(
            "Wikidata découverte salle — rejet département incompatible nom={} deptSalle={}"
                + " codePostalCandidat={}",
            nomRecherche,
            salle.dept(),
            codePostal);
        continue;
      }

      String qid = itemUri.substring(itemUri.lastIndexOf('/') + 1);
      EnrichissementSalle enrichissement =
          new EnrichissementSalle(qid, labelCorrespondant, communeLabel, null, null);
      cache.put(qid, enrichissement);
      return Optional.of(enrichissement);
    }
    log.info("Wikidata découverte salle — aucun candidat exact nom={}", nomRecherche);
    return Optional.empty();
  }

  /// Vérifie que la commune du candidat Wikidata ne contredit pas la ville connue de la salle
  /// Strapontin.
  ///
  /// <p>Constaté en production : le repli sur nom tronqué peut retenir en rang 0 un candidat au nom
  /// générique partagé par plusieurs communes (ex. "Collegiale Saint" → "collégiale Saint-Agricol
  /// d'Avignon", alors que la salle recherchée est à Chinon) — aucune des désambiguïsations de
  /// {@link #trouverLabelCorrespondant} ne porte sur la localisation. Ce garde-fou n'écarte que les
  /// cas où les deux villes sont connues et diffèrent ; une ville inconnue d'un côté ou de l'autre
  /// ne bloque rien (reste au niveau de risque actuel, pas de régression sur les cas déjà
  /// couverts).
  ///
  /// @param villeSalle ville connue de la salle Strapontin ({@link SalleAEnrichir#ville}), peut
  ///   être {@code null}
  /// @param communeLabel commune du candidat Wikidata (P131), peut être {@code null}
  /// @return {@code true} si les deux villes sont connues et ne correspondent pas
  private static boolean villeIncompatible(String villeSalle, String communeLabel) {
    if (villeSalle == null || villeSalle.isBlank()) return false;
    if (communeLabel == null || communeLabel.isBlank()) return false;
    return !Normalisation.normaliserVille(villeSalle)
        .equals(Normalisation.normaliserVille(communeLabel));
  }

  /// Vérifie que le département du candidat Wikidata (dérivé du code postal de sa commune) ne
  /// contredit pas le département connu de la salle Strapontin.
  ///
  /// <p>Complète {@link #villeIncompatible} : {@code salles.dept} est renseigné dès la création de
  /// la salle à partir d'un concert réel (fiable), alors que {@code salles.ville} peut rester vide
  /// ou fausse au moment de cet enrichissement (angle mort constaté en production sur plusieurs
  /// salles rattachées à tort à une entité Wikidata sans rapport — gare, arrêt de tram, bâtiment
  /// homonyme — faute de ville connue pour bloquer le rapprochement). Même politique que {@link
  /// #villeIncompatible} : ne rejette que si les deux départements sont connus et diffèrent.
  ///
  /// @param deptSalle département connu de la salle Strapontin ({@link SalleAEnrichir#dept}), peut
  ///   être {@code null}
  /// @param codePostal code postal de la commune du candidat Wikidata (P281), peut être {@code
  ///   null}
  /// @return {@code true} si les deux départements sont connus et ne correspondent pas
  private static boolean deptIncompatible(String deptSalle, String codePostal) {
    if (deptSalle == null || deptSalle.isBlank()) return false;
    String deptCandidat = Normalisation.extraireDeptFrancais(codePostal);
    if (deptCandidat == null) return false;
    return !deptSalle.equals(deptCandidat);
  }

  // Nombre minimal de tokens significatifs cibles pour appliquer le containment asymétrique
  // évite qu'un nom d'un seul mot ("galaxie") soit considéré contenu dans n'importe
  // quel candidat plus long au seul motif qu'il partage ce mot.
  private static final int TAILLE_MIN_TOKENS_CONTAINMENT = 2;

  /// Cherche une correspondance floue ({@link CorrespondanceSalle#estDoublonSalle}, complété par un
  /// containment de tokens asymétrique, cf. {@link #estCorrespondanceAsymetrique}) sur le libellé
  /// principal Wikidata ou l'un de ses alias.
  ///
  /// <p>Les alias sont vérifiés en plus du libellé principal, pas à la place : les grandes salles
  /// changent régulièrement de nom commercial (sponsoring) et Wikidata conserve souvent l'ancien
  /// nom comme libellé principal, le nom actuel n'étant qu'un alias.
  ///
  /// @param nomNormCible nom de la salle Strapontin, normalisé ({@link SalleAEnrichir#nomNorm})
  /// @param itemLabel libellé principal du candidat Wikidata (peut être {@code null})
  /// @param aliases alias du candidat séparés par {@code "|"} (peut être {@code null} ou vide)
  /// @return le libellé (principal ou alias) qui correspond, ou {@code null} si aucun
  private static String trouverLabelCorrespondant(
      String nomNormCible, String itemLabel, String aliases) {
    if (itemLabel != null
        && estCorrespondanceAsymetrique(nomNormCible, Normalisation.normaliserTexte(itemLabel))) {
      return itemLabel;
    }
    if (aliases == null || aliases.isBlank()) return null;
    for (String alias : aliases.split("\\|")) {
      if (estCorrespondanceAsymetrique(nomNormCible, Normalisation.normaliserTexte(alias))) {
        return alias;
      }
    }
    return null;
  }

  /// Complète {@link CorrespondanceSalle#estDoublonSalle} par un containment de tokens asymétrique
  /// : couvre le cas où Wikidata combine l'ancien et le nouveau nom dans un seul alias (constaté en
  /// production : « Dôme de Paris » — nom Strapontin actuel — n'est rapproché d'aucun candidat
  /// mwapi malgré l'existence de l'entité correcte en rang 0, son libellé principal étant « Palais
  /// des sports » et son alias le plus proche « Dôme de Paris - Palais des Sports »). Cet alias est
  /// trop long relativement à notre nom pour satisfaire le containment de caractères (ratio {@code
  /// < 0.5}) ou le Jaccard symétrique (0.4, sous le seuil 0.5) de {@code estDoublonSalle} — les
  /// deux critères pénalisent le nombre de mots supplémentaires côté Wikidata, alors que ces mots
  /// supplémentaires sont justement l'ancien nom, pas du bruit.
  ///
  /// <p>Asymétrique à dessein : exige que <strong>tous</strong> les tokens significatifs de notre
  /// nom (le plus souvent court, le nom commercial actuel) soient présents dans le candidat
  /// Wikidata (souvent plus long, combinant plusieurs noms), jamais l'inverse — un candidat plus
  /// court que notre nom reste filtré par {@code estDoublonSalle} seul. Le {@value
  /// #TAILLE_MIN_TOKENS_CONTAINMENT} minimum de tokens cible évite qu'un nom d'un seul mot
  /// corresponde à n'importe quel candidat le contenant (même risque de faux positif qu'un Jaccard
  /// non borné, documenté dans {@link CorrespondanceSalle}).
  ///
  /// @param nomNormCible nom Strapontin normalisé
  /// @param candidatNorm libellé ou alias Wikidata normalisé
  /// @return {@code true} si les tokens significatifs de {@code nomNormCible} sont tous inclus dans
  ///   ceux de {@code candidatNorm}
  private static boolean estCorrespondanceAsymetrique(String nomNormCible, String candidatNorm) {
    if (CorrespondanceSalle.estDoublonSalle(nomNormCible, candidatNorm)) return true;
    Set<String> tokensCible = CorrespondanceSalle.tokensSignificatifs(nomNormCible);
    if (tokensCible.size() < TAILLE_MIN_TOKENS_CONTAINMENT) return false;
    Set<String> tokensCandidat = CorrespondanceSalle.tokensSignificatifs(candidatNorm);
    return tokensCandidat.containsAll(tokensCible);
  }

  private static String construireRequeteDecouverte(String nom) {
    return """
    SELECT ?item ?itemLabel ?communeLabel ?num
      (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (SAMPLE(?cp) AS ?codePostal)
    WHERE {
      SERVICE wikibase:mwapi {
        bd:serviceParam wikibase:api "EntitySearch".
        bd:serviceParam wikibase:endpoint "www.wikidata.org".
        bd:serviceParam mwapi:search "%s".
        bd:serviceParam mwapi:language "fr".
        ?item wikibase:apiOutputItem mwapi:item.
        ?num wikibase:apiOrdinal true.
      }
      ?item wdt:P625 [] .
      ?item wdt:P17 wd:Q142 .
      FILTER NOT EXISTS {
        ?item wdt:P31 ?typeExclu .
        VALUES ?typeExclu { wd:Q3455524 wd:Q6465 wd:Q484170 wd:Q55488 wd:Q2175765 }
      }
      OPTIONAL { ?item skos:altLabel ?alias . FILTER(LANG(?alias) = "fr") }
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
        .formatted(ClientSparqlWikidata.echapperLitteral(nom));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> extraireBindings(Map<String, Object> data) {
    var results = (Map<String, Object>) data.get("results");
    if (results == null) return List.of();
    var bindings = (List<Map<String, Object>>) results.get("bindings");
    return bindings != null ? bindings : List.of();
  }
}
