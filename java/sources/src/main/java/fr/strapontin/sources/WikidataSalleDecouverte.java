/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.Normalisation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Découverte d'une entité Wikidata par recherche de nom, sans Q-ID connu
 * ({@code wikibase:mwapi}, extrait de {@link WikidataSalleSource}) — filtre,
 * coordonnées, alias et périmètre France : détails, wiki Sources-de-données.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class WikidataSalleDecouverte {

  private static final Logger LOG = LoggerFactory.getLogger(WikidataSalleDecouverte.class);

  /**
   * Sépare le premier séparateur significatif de sous-salle/qualificatif
   * ({@code -}, {@code /}, {@code (}) d'un nom de salle, le cas échéant.
   */
  private static final Pattern SEPARATEUR_SOUS_SALLE = Pattern.compile("[-/(]");

  private static final Pattern PONCTUATION_FINALE = Pattern.compile("[\\p{Punct}\\s]+$");

  // Nombre minimal de tokens significatifs cibles pour appliquer le containment
  // asymétrique évite qu'un nom d'un seul mot ("galaxie") soit considéré
  // contenu dans n'importe quel candidat plus long au seul motif qu'il partage
  // ce mot.
  private static final int TAILLE_MIN_TOKENS_CONTAINMENT = 2;

  // Types Wikidata (P31) structurellement incompatibles avec un lieu de
  // spectacle : gare (Q3455524), arrêt de tram (Q6465), commune française
  // (Q484170), gare ferroviaire (Q55488), arrêt de transport en commun
  // (Q2175765), bar (Q187456), restaurant (Q11707), quotidien régional
  // (Q1002697), journal (Q11032), arrondissement municipal (Q702842, ex.
  // "13e arrondissement de Paris" retenu pour "Le 13e Art") — un candidat de
  // l'un de ces types est nécessairement une fausse correspondance, quel que
  // soit son score de similarité de nom. Exposée pour permettre à
  // {@code CoherenceWikidataSalleService} de revalider les {@code wikidata_id}
  // déjà stockés contre ces mêmes types (garde-fou introduit après coup ne
  // s'appliquant pas rétroactivement aux découvertes antérieures).
  /* package-private : réutilisé par WikidataSalleSource (même package). */
  static final Set<String> TYPES_EXCLUS =
      Set.of(
          "Q3455524",
          "Q6465",
          "Q484170",
          "Q55488",
          "Q2175765",
          "Q187456",
          "Q11707",
          "Q1002697",
          "Q11032",
          "Q702842",
          // Voies : impasse, route, rue, boulevard, voie de communication.
          "Q12731",
          "Q34442",
          "Q79007",
          "Q54114",
          "Q83620");

  // Rempli par decouvrir() sur divergence GPS, vidé par preleverAmbiguites() —
  // même patron que WikidataArtisteSource.ambiguites.
  private final Map<UUID, Map<String, Object>> ambiguites = new HashMap<>();

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementSalle> cache;

  /* package-private : appelé par WikidataSalleSource (même package). */
  Map<UUID, Map<String, Object>> preleverAmbiguites() {
    final Map<UUID, Map<String, Object>> resultat = new HashMap<>(ambiguites);
    ambiguites.clear();
    return resultat;
  }

  /* package-private : instancié uniquement par WikidataSalleSource (même package). */
  /**
   * @param client client SPARQL Wikidata partagé
   * @param cache cache des enrichissements découverts, partagé avec {@link
   *     WikidataSalleSource}
   */
  WikidataSalleDecouverte(
      final ClientSparqlWikidata client, final Cache<String, EnrichissementSalle> cache) {
    this.client = client;
    this.cache = cache;
  }

  /* package-private : appelé par WikidataSalleSource (même package). */
  Optional<EnrichissementSalle> decouvrir(final SalleAEnrichir salle) {
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

  // la recherche mwapi:search échoue quasi systématiquement dès que le nom
  // Strapontin porte un suffixe de sous-salle/qualificatif ("Zenith Toulouse
  // Metropole" → 0 résultat, "Zenith de Toulouse" trouvé immédiatement), alors
  // que l'entité existe bien sur Wikidata — l'échec se produit dans l'appel
  // mwapi, avant même la comparaison des candidats. La validation du candidat
  // reste faite contre salle.nomNorm() (nom complet), inchangée — seule la
  // requête de recherche envoyée à Wikidata utilise le nom tronqué.
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

    LOG.info(
        "Wikidata découverte salle — repli sur nom tronqué nom={} tronque={}",
        salle.nom(),
        nomTronque);
    return decouvrirParNom(salle, nomTronque);
  }

  // Convention de nommage fréquente côté sources
  // (Ticketmaster/Eventim) : le nom de la salle est suffixé de sa
  // ville sans séparateur ("Salle Poirel Nancy", "La Belle Electrique
  // Grenoble") — vérifié en direct contre l'API Wikidata, la recherche mwapi
  // échoue sur le nom complet mais trouve l'entité sur le seul nom de salle une
  // fois la ville retirée. Un seul mot est retiré, jamais une troncature
  // récursive : au-delà, le risque de réduire le terme de recherche à un mot
  // générique non discriminant l'emporte sur le gain (une ville à qualificatif
  // composé, ex. "Vitry Sur Seine", n'est pas couverte par ce repli). La
  // validation finale reste faite contre le nom complet ({@link
  // #decouvrirParNom}), ce repli n'élargit que l'ensemble de candidats
  // interrogés, jamais le critère d'acceptation.
  private Optional<EnrichissementSalle> decouvrirSansDernierMot(final SalleAEnrichir salle) {
    final String nom = salle.nom().trim();
    final int dernierEspace = nom.lastIndexOf(' ');
    if (dernierEspace <= 0) {
      return Optional.empty();
    }
    // trim() ne retire que les espaces — un séparateur ("-", "/") laissé en
    // bout de chaîne produirait une recherche mwapi inutilement sale (ex.
    // "Zenith Toulouse -")
    final String nomTronque =
        PONCTUATION_FINALE.matcher(nom.substring(0, dernierEspace)).replaceAll("").trim();
    // au moins deux mots restants — un seul mot est trop générique pour être
    // discriminant
    if (!nomTronque.contains(" ")) {
      return Optional.empty();
    }

    LOG.info(
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

    final List<Map<String, Object>> bindings = client.bindingsDeDecouverte(data);
    for (final Map<String, Object> binding : bindings) {
      final Optional<EnrichissementSalle> candidat =
          evaluerCandidatDecouverte(salle, binding, nomRecherche);
      if (candidat.isPresent()) {
        return candidat;
      }
    }
    LOG.info("Wikidata découverte salle — aucun candidat exact nom={}", nomRecherche);
    return Optional.empty();
  }

  /**
   * Évalue un binding candidat : correspondance nom, cohérence ville/dept.
   *
   * @param salle salle recherchée
   * @param binding binding SPARQL brut
   * @param nomRecherche nom utilisé pour la requête (logs de rejet)
   * @return l'enrichissement si ce candidat est accepté, vide sinon
   */
  private Optional<EnrichissementSalle> evaluerCandidatDecouverte(
      final SalleAEnrichir salle, final Map<String, Object> binding, final String nomRecherche) {
    final String itemUri = ClientSparqlWikidata.valeurBinding(binding, "item");
    if (itemUri == null) {
      return Optional.empty();
    }
    final String itemLabel = ClientSparqlWikidata.valeurBinding(binding, "itemLabel");
    final String aliases = ClientSparqlWikidata.valeurBinding(binding, "aliases");
    final String communeLabel = ClientSparqlWikidata.valeurBinding(binding, "communeLabel");
    final String codePostal = ClientSparqlWikidata.valeurBinding(binding, "codePostal");
    final double[] coord =
        ClientSparqlWikidata.parserCoordonnees(
            ClientSparqlWikidata.valeurBinding(binding, "coord"));
    final Double coordLat = coord != null ? coord[1] : null;
    final Double coordLon = coord != null ? coord[0] : null;
    final boolean localisationConnue =
        (communeLabel != null && !communeLabel.isBlank())
            || (codePostal != null && !codePostal.isBlank());
    final String labelCorrespondant =
        trouverLabelCorrespondant(salle.nomNorm(), itemLabel, aliases, localisationConnue);
    if (labelCorrespondant == null) {
      return Optional.empty();
    }

    if (localisationIncompatible(salle, communeLabel, codePostal, nomRecherche)) {
      return Optional.empty();
    }

    if (ValidationCoordonneesSalle.incompatibles(salle, coordLat, coordLon)) {
      LOG.warn(
          "Wikidata découverte salle — rejet coordonnées divergentes nom={} salleId={}",
          nomRecherche,
          salle.id());
      ambiguites.put(salle.id(), detailsDivergence(salle, itemUri, itemLabel, coordLat, coordLon));
      return Optional.empty();
    }

    final String qid = itemUri.substring(itemUri.lastIndexOf('/') + 1);
    final EnrichissementSalle enrichissement =
        new EnrichissementSalle(
            qid, labelCorrespondant, communeLabel, null, null, coordLat, coordLon);
    cache.put(qid, enrichissement);
    return Optional.of(enrichissement);
  }

  // Le candidat rejeté et l'écart constaté : sans eux le signalement n'est pas exploitable.
  private static Map<String, Object> detailsDivergence(
      final SalleAEnrichir salle,
      final String itemUri,
      final String itemLabel,
      final Double coordLat,
      final Double coordLon) {
    final Map<String, Object> details = new LinkedHashMap<>();
    details.put("wikidataId", itemUri.substring(itemUri.lastIndexOf('/') + 1));
    if (itemLabel != null && !itemLabel.isBlank()) {
      details.put("libelleWikidata", itemLabel);
    }
    if (salle.latitude() != null
        && salle.longitude() != null
        && coordLat != null
        && coordLon != null) {
      details.put(
          "ecartMetres",
          Math.round(DistanceGeo.metres(salle.latitude(), salle.longitude(), coordLat, coordLon)));
    }
    return details;
  }

  // regroupe les deux garde-fous de localisation (ville, département) et leur
  // journalisation de rejet — extrait d'evaluerCandidatDecouverte pour rester
  // sous la limite de 50 lignes/méthode.
  private boolean localisationIncompatible(
      final SalleAEnrichir salle,
      final String communeLabel,
      final String codePostal,
      final String nomRecherche) {
    if (villeIncompatible(salle.ville(), communeLabel)) {
      LOG.info(
          "Wikidata découverte salle — rejet commune incompatible nom={} villeSalle={}"
              + " communeCandidat={}",
          nomRecherche,
          salle.ville(),
          communeLabel);
      return true;
    }
    if (deptIncompatible(salle.dept(), codePostal)) {
      LOG.info(
          "Wikidata découverte salle — rejet département incompatible nom={} deptSalle={}"
              + " codePostalCandidat={}",
          nomRecherche,
          salle.dept(),
          codePostal);
      return true;
    }
    return false;
  }

  /**
   * Vérifie que la commune du candidat Wikidata ne contredit pas la ville
   * connue (rejette seulement si les deux sont connues et diffèrent).
   * @param villeSalle ville connue de la salle Strapontin, ou {@code null}
   * @param communeLabel commune du candidat Wikidata (code INSEE), ou {@code null}
   * @return {@code true} si les deux villes sont connues et diffèrent
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
   * Vérifie que le département du candidat Wikidata (code postal de sa
   * commune) ne contredit pas celui de la salle, complétant {@link
   * #villeIncompatible}.
   * @param deptSalle département connu de la salle Strapontin, ou {@code null}
   * @param codePostal code postal du candidat (P281), ou {@code null}
   * @return {@code true} si les deux départements sont connus et diffèrent
   */
  private static boolean deptIncompatible(final String deptSalle, final String codePostal) {
    if (deptSalle == null || deptSalle.isBlank()) {
      return false;
    }
    final String deptCandidat = Normalisation.extraireDeptFrancais(codePostal);
    return deptCandidat != null && !deptSalle.equals(deptCandidat);
  }

  /**
   * Cherche une correspondance floue sur le libellé Wikidata ou un alias.
   * @param nomNormCible nom de la salle Strapontin, normalisé
   * @param itemLabel libellé principal du candidat, ou {@code null}
   * @param aliases alias du candidat séparés par {@code "|"}, ou {@code null}
   * @param localisationConnue transmis à estDoublonSalle (désactive le pivot)
   * @return le libellé (principal ou alias) qui correspond, ou {@code null}
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
   * Complète estDoublonSalle par un containment de tokens asymétrique (alias
   * combinant ancien/nouveau nom, ex. « Dôme de Paris », voir wiki).
   * @param nomNormCible nom Strapontin normalisé
   * @param candidatNorm libellé ou alias Wikidata normalisé
   * @param localisationConnue transmis tel quel à estDoublonSalle
   * @return {@code true} si tous les tokens de {@code nomNormCible} sont inclus
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
      %s
      SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
    }
    GROUP BY ?item ?itemLabel ?communeLabel ?num
    ORDER BY ?num
    LIMIT 5
    """
        .formatted(
            ClientSparqlWikidata.echapperLitteral(nom),
            typesExclusSparql,
            ClientSparqlWikidata.MOTIF_COMMUNE);
  }
}
