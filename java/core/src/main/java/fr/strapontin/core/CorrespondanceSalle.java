/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Détermine si deux noms de salle normalisés désignent la même salle,
 * extrait de {@code ReconciliateurSalles} pour être réutilisable par
 * {@code sources} (découverte Wikidata, sans dépendance circulaire).
 *
 * @since 2.6.0
 * @author Kwaite
 */
public final class CorrespondanceSalle {

  // Seuils Levenshtein ≥ 0.75 + containment ratio court/long ≥ 0.5. 0.75 évite
  // les faux positifs entre villes ("zenith bordeaux"/"zenith caen" sim=0.61 →
  // false) tout en couvrant les variantes légitimes ("l olympia"/"olympia"
  // sim=0.78 → true).
  private static final Pattern SUFFIXE_ARRONDISSEMENT =
      Pattern.compile("\\s+(paris|lyon|marseille)\\s+\\d{1,2}$");
  private static final double SEUIL_SIMILARITE_SALLE = 0.75;
  private static final int LONGUEUR_MIN_CONTAINMENT_SALLE = 4;
  private static final double RATIO_MIN_CONTAINMENT_SALLE = 0.5;
  // Longueur minimale pour inclure un token dans le calcul Jaccard (filtre
  // "le", "de", "la"…).
  private static final int LONGUEUR_MIN_TOKEN_JACCARD = 3;
  private static final double SEUIL_JACCARD_SALLE = 0.5;
  // Critère pivot : token commun ≥ LONGUEUR_MIN_PIVOT + Jaccard >
  // SEUIL_JACCARD_PIVOT. Couvre les variantes Eventim "grand kursaal"/"kursaal
  // besancon" (Jaccard=0.33, pivot≥7).
  private static final int LONGUEUR_MIN_PIVOT = 7;
  private static final double SEUIL_JACCARD_PIVOT = 0.3;
  // Mots-types de salle génériques en français, partagés nationalement par de
  // nombreux lieux sans rapport (des dizaines de "Palais des
  // Congrès"/"Zénith"/"Théâtre Municipal" existent en France) — exclus du
  // calcul de similarité par tokens (Jaccard et pivot), qui doit porter sur ce
  // qui distingue réellement deux salles, pas sur leur type architectural
  // commun.
  //
  // Visibilité publique : réutilisé tel quel par la suggestion de mots
  // génériques (worker), qui ne doit jamais re-suggérer un mot déjà connu de
  // cette liste.
  public static final Set<String> MOTS_GENERIQUES_SALLE =
      Set.of(
          "theatre",
          "chateau",
          "casino",
          "palais",
          "centre",
          "congres",
          "espace",
          "salle",
          "forum",
          "complexe",
          "colisee",
          "expositions",
          "culturel",
          "metropole",
          "municipal",
          "antique",
          "parc",
          "barriere",
          // "eglise"/"saint"/"chapelle" : des dizaines d'édifices religieux
          // français partagent ces tokens sans aucun rapport entre eux (ex.
          // "Eglise Saint-Ephrem" / "Eglise Saint-Eustache", ou "La
          // Sainte-Chapelle" / "Chapelle Saint-Symphorien", deux lieux
          // parisiens réels et distincts fusionnés à tort via le seul pivot
          // "chapelle") — leur seule présence gonflait le Jaccard spécifique
          // bien au-delà du seuil entre deux salles réellement distinctes.
          "eglise",
          "saint",
          "chapelle");

  // Longueur minimale d'un token spécifique pour servir de base au containment
  // partiel asymétrique — évite un containment trivial sur un token déjà très
  // court.
  private static final int LONGUEUR_MIN_CONTAINMENT_TOKEN = 4;

  private static final String SEPARATEUR_QUALIFICATIF = " - ";

  private CorrespondanceSalle() {}

  private static boolean estDoublonTexte(
      final String nomA,
      final String nomB,
      final double seuilSimilarite,
      final int longueurMinContainment,
      final double ratioMinContainment,
      final boolean containmentAutorise) {
    if (nomA.equals(nomB)) {
      return true;
    }
    // Le containment de caractères (un nom court entièrement préfixe/suffixe
    // d'un nom plus long) n'a aucun pouvoir discriminant propre — "la prade"
    // est un préfixe strict de "la pradella", deux lieux réels sans rapport —
    // désactivé au même titre que le pivot quand aucun signal de localisation
    // indépendant ne peut confirmer le candidat.
    return DistanceTexte.similarite(nomA, nomB) >= seuilSimilarite
        || (containmentAutorise
            && UtilitairesChaine.estContainmentRatio(
                nomA, nomB, longueurMinContainment, ratioMinContainment));
  }

  /**
   * Découpe une chaîne normalisée en tokens significatifs (filtre les mots de
   * moins de {@value #LONGUEUR_MIN_TOKEN_JACCARD} caractères — articles "le",
   * "de", "la"…).
   *
   * @param nomNorm chaîne normalisée
   * @return ensemble des tokens significatifs
   */
  public static Set<String> tokensSignificatifs(final String nomNorm) {
    final Set<String> tokens = new HashSet<>(Arrays.asList(nomNorm.split("\\s+")));
    tokens.removeIf(t -> t.length() < LONGUEUR_MIN_TOKEN_JACCARD);
    return tokens;
  }

  /**
   * Découpe une chaîne normalisée en tokens significatifs, en excluant les
   * mots-types de salle génériques — un token comme "theatre" ou "palais"
   * ne prouve rien seul, partagé nationalement par des centaines de lieux.
   * @param nomNorm chaîne normalisée
   * @return ensemble des tokens significatifs et spécifiques
   */
  private static Set<String> tokensSpecifiques(final String nomNorm) {
    final Set<String> tokens = new HashSet<>(tokensSignificatifs(nomNorm));
    tokens.removeAll(MOTS_GENERIQUES_SALLE);
    return tokens;
  }

  /**
   * Calcule le coefficient de Jaccard sur les tokens des deux chaînes
   * (tokens ≥ {@value #LONGUEUR_MIN_TOKEN_JACCARD} car., 1.0 si les deux
   * unions sont vides).
   * @param nomA première chaîne normalisée
   * @param nomB deuxième chaîne normalisée
   * @return coefficient de Jaccard entre 0.0 et 1.0
   */
  public static double jaccardTokens(final String nomA, final String nomB) {
    final Set<String> tokensA = tokensSignificatifs(nomA);
    final Set<String> tokensB = tokensSignificatifs(nomB);
    if (tokensA.isEmpty() && tokensB.isEmpty()) {
      return 1.0;
    }
    final Set<String> union = new HashSet<>(tokensA);
    union.addAll(tokensB);
    if (union.isEmpty()) {
      return 0.0;
    }
    final Set<String> intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    return (double) intersection.size() / union.size();
  }

  /**
   * Vérifie si chaque token du nom court est contenu dans un token du nom
   * long (ex. "Accor Arena"/"Accorhotels Arena"), couverture intégrale.
   * @param specCourt tokens spécifiques du nom le plus court
   * @param specLong tokens spécifiques du nom le plus long
   * @return {@code true} si tous les tokens de specCourt sont couverts
   */
  private static boolean estContainmentTokensPartiel(
      final Set<String> specCourt, final Set<String> specLong) {
    if (specCourt.isEmpty() || specLong.isEmpty()) {
      return false;
    }
    for (final String tokenCourt : specCourt) {
      if (tokenCourt.length() < LONGUEUR_MIN_CONTAINMENT_TOKEN) {
        return false;
      }
      // La garde de longueur doit aussi s'appliquer au token du nom long : sans
      // elle, un article court comme "les" (issu d'un nom mal désapostrophé,
      // ex. "lessaion") valide trivialement un containment sans aucun rapport
      // sémantique réel avec le nom recherché.
      final boolean couvert =
          specLong.stream()
              .filter(t -> t.length() >= LONGUEUR_MIN_CONTAINMENT_TOKEN)
              .anyMatch(t -> t.contains(tokenCourt) || tokenCourt.contains(t));
      if (!couvert) {
        return false;
      }
    }
    return true;
  }

  /**
   * Vérifie si deux noms normalisés désignent la même salle (union de 5
   * critères : Levenshtein/containment, Jaccard, pivot, égalité sans
   * espaces, containment partiel — détail : CLAUDE.md, Dédoublonnage).
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les salles sont considérées identiques
   */
  public static boolean estDoublonSalle(final String nomA, final String nomB) {
    return estDoublonSalle(nomA, nomB, true);
  }

  /**
   * Variante désactivant pivot et containment de caractères (les deux plus
   * faibles des cinq, cf. CLAUDE.md) — réservé sans signal de localisation.
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @param pivotAutorise {@code false} pour exclure pivot et containment
   * @return {@code true} si les salles sont considérées identiques
   */
  public static boolean estDoublonSalle(
      final String nomA, final String nomB, final boolean pivotAutorise) {
    return estDoublonTexte(
            nomA,
            nomB,
            SEUIL_SIMILARITE_SALLE,
            LONGUEUR_MIN_CONTAINMENT_SALLE,
            RATIO_MIN_CONTAINMENT_SALLE,
            pivotAutorise)
        || estDoublonParMots(nomA, nomB, pivotAutorise);
  }

  /**
   * Doublon établi par les seuls mots du nom, sans similarité de caractères
   * ni mot pivot : deux églises voisines (« eglise saint ephrem » / « eglise
   * saint sulpice ») partagent assez de caractères pour tromper Levenshtein.
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les mots spécifiques désignent la même salle
   */
  public static boolean estDoublonSalleParMots(final String nomA, final String nomB) {
    return estDoublonParMots(nomA, nomB, false);
  }

  private static boolean estDoublonParMots(
      final String nomA, final String nomB, final boolean pivotAutorise) {
    if (UtilitairesChaine.estEgaliteSansEspaces(nomA, nomB)) {
      return true;
    }

    // Tokens seulement : Eventim suffixe ses lieux de l'arrondissement (« paris 08 »),
    // dont la ville gonflerait le Jaccard entre deux lieux distincts d'une même ville.
    final String tokensA = SUFFIXE_ARRONDISSEMENT.matcher(nomA).replaceFirst("");
    final String tokensB = SUFFIXE_ARRONDISSEMENT.matcher(nomB).replaceFirst("");
    final Set<String> tokA = tokensSignificatifs(tokensA);
    final Set<String> tokB = tokensSignificatifs(tokensB);
    final Set<String> specA = tokensSpecifiques(tokensA);
    final Set<String> specB = tokensSpecifiques(tokensB);
    final Set<String> intersectionSpec = intersection(specA, specB);
    final double jaccardSpec = jaccard(specA, specB, intersectionSpec);

    if (estJaccardSpecifiqueSuffisant(specA, specB, jaccardSpec)) {
      return true;
    }
    return (pivotAutorise
            && estPivotSpecifique(tokA, tokB, specA, specB, jaccardSpec, intersectionSpec))
        || estContainmentPartielSuffisant(specA, specB);
  }

  private static Set<String> intersection(
      final Set<String> ensembleA, final Set<String> ensembleB) {
    final Set<String> resultat = new HashSet<>(ensembleA);
    resultat.retainAll(ensembleB);
    return resultat;
  }

  private static double jaccard(
      final Set<String> ensembleA, final Set<String> ensembleB, final Set<String> intersection) {
    final Set<String> union = new HashSet<>(ensembleA);
    union.addAll(ensembleB);
    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
  }

  // Au moins un token spécifique en commun requis — sans quoi deux noms
  // entièrement génériques (déjà couverts par l'égalité stricte) ou sans mot
  // distinctif partagé ne doivent jamais être déclarés doublons sur ce seul
  // critère.
  private static boolean estJaccardSpecifiqueSuffisant(
      final Set<String> specA, final Set<String> specB, final double jaccardSpec) {
    return !(specA.isEmpty() && specB.isEmpty()) && jaccardSpec > SEUIL_JACCARD_SALLE;
  }

  // Pivot : requiert ≥ 2 tokens dans chaque nom pour éviter les faux positifs
  // de type "galaxie" (1 token) vs "le galaxie amneville", et ≥ 2 tokens
  // spécifiques : un nom réduit au seul pivot après retrait des mots-types
  // ("Théâtre Montparnasse") vaut sinon pour tout lieu qui le contient comme
  // simple nom de quartier ("Théâtre de Poche Montparnasse").
  private static boolean estPivotSpecifique(
      final Set<String> tokA,
      final Set<String> tokB,
      final Set<String> specA,
      final Set<String> specB,
      final double jaccardSpec,
      final Set<String> intersectionSpec) {
    return tokA.size() >= 2
        && tokB.size() >= 2
        && specA.size() >= 2
        && specB.size() >= 2
        && jaccardSpec > SEUIL_JACCARD_PIVOT
        && intersectionSpec.stream().anyMatch(t -> t.length() >= LONGUEUR_MIN_PIVOT);
  }

  // Garde-fou sur le nombre de tokens SPÉCIFIQUES (pas bruts) de chaque côté —
  // sans lui, un nom réduit à un unique token spécifique après retrait des
  // mots-types génériques ("Casino de Paris" → seul "paris" reste après retrait
  // de "casino") correspond trivialement à n'importe quel autre nom partageant
  // ce token comme simple nom de ville ("Théâtre Rive Gauche Paris 14",
  // "Cathédrale Américaine Paris 08"...), sans aucun rapport avec le lieu
  // recherché.
  private static boolean estContainmentPartielSuffisant(
      final Set<String> specA, final Set<String> specB) {
    if (specA.size() < 2 || specB.size() < 2) {
      return false;
    }
    final Set<String> specCourt = specA.size() <= specB.size() ? specA : specB;
    final Set<String> specLong = specA.size() <= specB.size() ? specB : specA;
    return estContainmentTokensPartiel(specCourt, specLong);
  }

  // Le séparateur ne survit pas à normaliserTexte (tirets remplacés par une
  // espace) — l'extraction opère donc sur le nom brut, jamais sur nom_norm.
  private static String extraireQualificatif(final String nomBrut) {
    final int idx = nomBrut.lastIndexOf(SEPARATEUR_QUALIFICATIF);
    if (idx <= 0) {
      return null;
    }
    final String qualif = nomBrut.substring(idx + SEPARATEUR_QUALIFICATIF.length()).trim();
    return qualif.isEmpty() ? null : qualif;
  }

  /**
   * Extrait la partie complexe/base d'un nom qualifié (ex. "La Seine
   * Musicale - Grande Seine" → "La Seine Musicale"), pour résoudre le
   * complexe avant de créer/retrouver la sous-salle correspondante.
   * @param nomBrut nom brut (non normalisé)
   * @return le nom de base, ou {@code null} si nomBrut ne porte aucun
   *     qualificatif
   */
  public static String extraireBase(final String nomBrut) {
    final int idx = nomBrut.lastIndexOf(SEPARATEUR_QUALIFICATIF);
    if (idx <= 0) {
      return null;
    }
    final String base = nomBrut.substring(0, idx).trim();
    return base.isEmpty() ? null : base;
  }

  /**
   * Indique si deux noms bruts portent un qualificatif de sous-salle
   * distinct — un complexe multi-salles ne fusionne pas deux halls
   * distincts au seul motif du nom du complexe partagé.
   * @param nomBrutA premier nom, non normalisé
   * @param nomBrutB second nom, non normalisé
   * @return {@code true} si les deux portent un qualificatif divergent
   */
  public static boolean qualificatifsDivergents(final String nomBrutA, final String nomBrutB) {
    final String qualifA = extraireQualificatif(nomBrutA);
    final String qualifB = extraireQualificatif(nomBrutB);
    if (qualifA == null || qualifB == null) {
      return false;
    }
    return !estDoublonSalle(
        Normalisation.normaliserTexte(qualifA), Normalisation.normaliserTexte(qualifB));
  }
}
