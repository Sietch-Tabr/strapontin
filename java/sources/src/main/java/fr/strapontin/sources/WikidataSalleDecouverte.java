/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Découverte d'une entité Wikidata par recherche de nom, quand aucun Q-ID n'est encore connu pour
 * la salle ({@code SERVICE wikibase:mwapi}, une requête par salle, non batchable).
 *
 * <p>Extrait de {@link WikidataSalleSource} pour rester sous la limite de 500 lignes par fichier.
 * Overpass n'est plus un passage obligé pour les salles (son instance publique traverse un incident
 * de surcharge documenté, voir {@link OverpassSalleSource}). <strong>Pas de filtre de type
 * positif</strong> (contrairement à {@link WikidataArtisteSource}) : l'ontologie Wikidata des
 * salles de spectacle est hétérogène (ex. « salle de spectacles polyvalente » Q1763828 est
 * sous-classe P279 de « gymnase » Q14092), un filtre transitif risquerait de faire correspondre des
 * équipements sportifs. Un filtre négatif exclut en revanche les entités de type
 * région/département/commune (P31 région administrative, département français, commune française)
 * ainsi que les infrastructures de transport (gare, arrêt de tram) : une recherche par nom peut
 * sinon retourner une entité administrative ou de transport homonyme plutôt que le lieu physique
 * recherché — constaté en production sur une gare (Villepinte) et un arrêt de tram (Bordeaux)
 * retenus à tort. Le département de la commune du candidat (déduit de son code postal, P281) est
 * également confronté au département connu de la salle Strapontin quand les deux sont disponibles
 * (voir {@link #deptIncompatible}) — {@code salles.ville} peut rester vide au moment de cet
 * enrichissement quand {@code salles.dept} est déjà fiable (renseigné dès la création à partir d'un
 * concert réel). En contrepartie, la requête exige {@code wdt:P625} (coordonnées géographiques) :
 * sans ce garde-fou, une recherche par nom seul peut retourner en premier résultat une œuvre
 * culturelle homonyme (constaté en production : pour « L'Olympia », le premier candidat mwapi est
 * un <em>album</em> d'Émilie Simon sorti en 2007, pas la salle) — un album/single/livre n'a pas de
 * coordonnées, une salle en a toujours une. Exception vérifiée : un candidat typé {@code
 * wd:Q1060829} (bâtiment de salle de concert) est accepté même sans coordonnées — constaté en
 * production sur « salle Paul-Fort » (Nantes), correcte mais structurellement exclue par l'exigence
 * de coordonnées seule, laissant un homonyme sans rapport (« salle Paul Bouin », Basse-Goulaine)
 * devenir l'unique candidat retourné. La désambiguïsation finale réutilise {@link
 * CorrespondanceSalle#estDoublonSalle} (module {@code core}, même algorithme que {@code
 * ReconciliateurSalles} côté worker : Levenshtein ≥ 0.75, Jaccard tokens, critère pivot) plutôt
 * qu'une simple égalité de chaîne — nécessaire pour absorber à la fois l'article défini français («
 * L'Olympia » ↔ « Olympia », via containment) et les suffixes descriptifs fréquents côté Strapontin
 * absents du libellé Wikidata (« Zenith Paris - la Villette » ↔ « Zénith Paris », via Jaccard
 * tokens). La comparaison porte aussi sur les <strong>alias</strong> ({@code skos:altLabel}) : les
 * grandes salles changent régulièrement de nom commercial (sponsoring) et Wikidata conserve souvent
 * l'ancien nom comme libellé principal, le nom actuel n'étant qu'un alias — constaté en production
 * : « Accor Arena » (nom Strapontin, actuel) n'est qu'un alias de l'entité dont le libellé
 * principal Wikidata est resté « Palais Omnisports de Paris-Bercy ». Sans vérifier les alias, ces
 * salles ne seraient jamais enrichies malgré une entrée Wikidata existante. Réutiliser l'algorithme
 * flou du projet plutôt qu'une correspondance exacte reste raisonnablement sûr ici : les candidats
 * mwapi sont déjà restreints aux résultats les plus pertinents pour le nom recherché, et les seuils
 * (Jaccard 0.5, pivot ≥7 chars) ont été calibrés dans le projet précisément pour rejeter les
 * homonymes inter-villes (« zenith bordeaux » vs « zenith caen »). <strong>Ce calibrage ne couvre
 * pas les homonymes internationaux</strong> — un nom propre français perd son identité après
 * suppression des accents et peut coïncider avec un nom anglais sans rapport (constaté en
 * production : « Orléans Arena » → « orleans arena », strictement identique à « Orleans Arena »
 * Q3356467, une salle de Las Vegas, Nevada — correspondance exacte immédiate, aucun seuil flou
 * impliqué). La requête exige donc {@code wdt:P17 wd:Q142} (pays = France) : Strapontin ne suit que
 * des concerts français (TM/Eventim/Digitick France), toute salle hors de ce périmètre est
 * nécessairement une fausse correspondance.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class WikidataSalleDecouverte {

  private static final Logger LOG = LoggerFactory.getLogger(WikidataSalleDecouverte.class);

  /**
   * Sépare le premier séparateur significatif de sous-salle/qualificatif ({@code -}, {@code /},
   * {@code (}) d'un nom de salle, le cas échéant.
   */
  private static final Pattern SEPARATEUR_SOUS_SALLE = Pattern.compile("[-/(]");

  private static final Pattern PONCTUATION_FINALE = Pattern.compile("[\\p{Punct}\\s]+$");

  // Nombre minimal de tokens significatifs cibles pour appliquer le containment asymétrique
  // évite qu'un nom d'un seul mot ("galaxie") soit considéré contenu dans n'importe
  // quel candidat plus long au seul motif qu'il partage ce mot.
  private static final int TAILLE_MIN_TOKENS_CONTAINMENT = 2;

  // Types Wikidata (P31) structurellement incompatibles avec un lieu de spectacle : gare
  // (Q3455524), arrêt de tram (Q6465), commune française (Q484170), gare ferroviaire (Q55488),
  // arrêt de transport en commun (Q2175765), bar (Q187456), restaurant (Q11707), quotidien
  // régional (Q1002697), journal (Q11032) — un candidat de l'un de ces types est nécessairement
  // une fausse correspondance, quel que soit son score de similarité de nom. Exposée pour permettre
  // à {@code CoherenceWikidataSalleService} de revalider les {@code wikidata_id} déjà stockés
  // contre ces mêmes types (garde-fou introduit après coup ne s'appliquant pas rétroactivement aux
  // découvertes antérieures).
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
          "Q11032");

  private final ClientSparqlWikidata client;
  private final Cache<String, EnrichissementSalle> cache;

  /**
   * @param client client SPARQL Wikidata partagé
   * @param cache cache des enrichissements découverts, partagé avec {@link WikidataSalleSource}
   */
  WikidataSalleDecouverte(
      final ClientSparqlWikidata client, final Cache<String, EnrichissementSalle> cache) {
    this.client = client;
    this.cache = cache;
  }

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

    LOG.info(
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

    for (final Map<String, Object> binding : ClientSparqlWikidata.extraireBindings(data)) {
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
   * Évalue un binding SPARQL candidat pour {@link #decouvrirParNom} : nom correspondant, cohérence
   * ville/dept, puis construction de l'enrichissement et mise en cache si accepté.
   *
   * @param salle salle recherchée
   * @param binding binding SPARQL brut
   * @param nomRecherche nom utilisé pour la requête (pour les logs de rejet)
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

  // regroupe les deux garde-fous de localisation (ville, département) et leur journalisation de
  // rejet — extrait d'evaluerCandidatDecouverte pour rester sous la limite de 50 lignes/méthode.
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
