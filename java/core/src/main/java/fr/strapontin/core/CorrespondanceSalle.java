/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/// Détermine si deux noms de salle normalisés désignent la même salle.
///
/// Extrait de `worker/persister/ReconciliateurSalles` (v2.5.7) vers `core` pour être réutilisable
/// par le module `sources` (découverte Wikidata par nom, v2.6.0) sans dépendance circulaire
/// `sources` → `worker`.
///
/// @since 2.6.0
/// @author Kwaite
public final class CorrespondanceSalle {

  // Seuils Levenshtein ≥ 0.75 + containment ratio court/long ≥ 0.5.
  // 0.75 évite les faux positifs entre villes ("zenith bordeaux"/"zenith caen" sim=0.61 → false)
  // tout en couvrant les variantes légitimes ("l olympia"/"olympia" sim=0.78 → true).
  private static final double SEUIL_SIMILARITE_SALLE = 0.75;
  private static final int LONGUEUR_MIN_CONTAINMENT_SALLE = 4;
  private static final double RATIO_MIN_CONTAINMENT_SALLE = 0.5;
  // Longueur minimale pour inclure un token dans le calcul Jaccard (filtre "le", "de", "la"…).
  static final int LONGUEUR_MIN_TOKEN_JACCARD = 3;
  private static final double SEUIL_JACCARD_SALLE = 0.5;
  // Critère pivot : token commun ≥ LONGUEUR_MIN_PIVOT + Jaccard > SEUIL_JACCARD_PIVOT.
  // Couvre les variantes Eventim "grand kursaal"/"kursaal besancon" (Jaccard=0.33, pivot≥7).
  private static final int LONGUEUR_MIN_PIVOT = 7;
  private static final double SEUIL_JACCARD_PIVOT = 0.3;
  // Mots-types de salle génériques en français, partagés nationalement par de nombreux lieux
  // sans rapport (des dizaines de "Palais des Congrès"/"Zénith"/"Théâtre Municipal" existent en
  // France) — exclus du calcul de similarité par tokens (Jaccard et pivot), qui doit porter sur
  // ce qui distingue réellement deux salles, pas sur leur type architectural commun.
  private static final Set<String> MOTS_GENERIQUES_SALLE =
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
          "barriere");

  private CorrespondanceSalle() {}

  private static boolean estDoublonTexte(
      String a,
      String b,
      double seuilSimilarite,
      int longueurMinContainment,
      double ratioMinContainment) {
    if (a.equals(b)) return true;
    if (Normalisation.similarite(a, b) >= seuilSimilarite) return true;
    String court = a.length() <= b.length() ? a : b;
    String plusLong = a.length() <= b.length() ? b : a;
    double ratio = (double) court.length() / plusLong.length();
    return court.length() >= longueurMinContainment
        && ratio >= ratioMinContainment
        && plusLong.contains(court);
  }

  /// Découpe une chaîne normalisée en tokens significatifs (filtre les mots de moins de {@value
  /// #LONGUEUR_MIN_TOKEN_JACCARD} caractères — articles "le", "de", "la"…).
  ///
  /// @param s chaîne normalisée
  /// @return ensemble des tokens significatifs
  public static Set<String> tokensSignificatifs(String s) {
    Set<String> tokens = new HashSet<>(Arrays.asList(s.split("\\s+")));
    tokens.removeIf(t -> t.length() < LONGUEUR_MIN_TOKEN_JACCARD);
    return tokens;
  }

  /// Découpe une chaîne normalisée en tokens significatifs, en excluant les mots-types de salle
  /// génériques — un token comme "theatre" ou "palais" ne prouve rien seul sur l'identité d'une
  /// salle, partagé nationalement par des centaines de lieux distincts.
  ///
  /// @param s chaîne normalisée
  /// @return ensemble des tokens significatifs et spécifiques (hors mots-types génériques)
  private static Set<String> tokensSpecifiques(String s) {
    Set<String> tokens = new HashSet<>(tokensSignificatifs(s));
    tokens.removeAll(MOTS_GENERIQUES_SALLE);
    return tokens;
  }

  /// Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
  ///
  /// <p>Seuls les tokens d'au moins {@value #LONGUEUR_MIN_TOKEN_JACCARD} caractères sont pris en
  /// compte (filtre les articles "le", "de", "la"…). Retourne 1.0 si les deux unions sont vides.
  ///
  /// @param a première chaîne normalisée
  /// @param b deuxième chaîne normalisée
  /// @return coefficient de Jaccard entre 0.0 et 1.0
  public static double jaccardTokens(String a, String b) {
    Set<String> tokensA = tokensSignificatifs(a);
    Set<String> tokensB = tokensSignificatifs(b);
    if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;
    Set<String> union = new HashSet<>(tokensA);
    union.addAll(tokensB);
    if (union.isEmpty()) return 0.0;
    Set<String> intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    return (double) intersection.size() / union.size();
  }

  // Longueur minimale d'un token spécifique pour servir de base au containment partiel
  // asymétrique — évite un containment trivial sur un token déjà très court.
  private static final int LONGUEUR_MIN_CONTAINMENT_TOKEN = 4;

  /// Vérifie si chaque token spécifique du nom le plus court est contenu (au sens sous-chaîne) dans
  /// au moins un token spécifique du nom le plus long.
  ///
  /// <p>Couvre le cas d'un mot inséré <em>au milieu</em> d'un autre plutôt qu'en préfixe/suffixe
  /// séparé par un espace (ex. "Accor Arena" / "Accorhotels Arena" — "hotels" concaténé sans espace
  /// forme un unique token "accorhotels", jamais égal ni partagé avec "accor"). Chaque token du nom
  /// court doit trouver une correspondance — pas un seul pivot isolé — ce qui limite le risque de
  /// faux positif par rapport à un simple partage d'un mot : contrairement au critère pivot, aucune
  /// longueur minimale de 7 caractères n'est requise ici, la couverture intégrale des tokens
  /// spécifiques du nom court sert de garde-fou à la place.
  ///
  /// @param specCourt tokens spécifiques du nom le plus court
  /// @param specLong tokens spécifiques du nom le plus long
  /// @return {@code true} si tous les tokens de {@code specCourt} sont couverts
  private static boolean estContainmentTokensPartiel(Set<String> specCourt, Set<String> specLong) {
    if (specCourt.isEmpty() || specLong.isEmpty()) return false;
    for (String tokenCourt : specCourt) {
      if (tokenCourt.length() < LONGUEUR_MIN_CONTAINMENT_TOKEN) return false;
      boolean couvert =
          specLong.stream().anyMatch(t -> t.contains(tokenCourt) || tokenCourt.contains(t));
      if (!couvert) return false;
    }
    return true;
  }

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// <p>Cinq critères en union :
  ///
  /// <ol>
  ///   <li>Levenshtein ≥ 0.75 ou containment (via {@link Normalisation#similarite})
  ///   <li>Jaccard sur tokens <strong>spécifiques</strong> (hors mots-types génériques) strict
  ///       {@code > 0.5} — insensible à l'ordre des mots
  ///   <li>Token pivot spécifique — token commun ≥ {@value #LONGUEUR_MIN_PIVOT} chars parmi les
  ///       tokens spécifiques et Jaccard spécifique {@code > }{@value #SEUIL_JACCARD_PIVOT}
  ///   <li>Égalité sans espaces — {@link Normalisation#normaliserTexte} remplace l'apostrophe par
  ///       une espace ("L'Olympia" → "l olympia"), mais une source qui omet totalement l'apostrophe
  ///       ("LOlympia") ne produit aucune espace à substituer ("lolympia") : sans ce critère, les
  ///       deux formes ne partagent aucun token commun
  ///   <li>Containment de tokens spécifiques partiel asymétrique — couvre un mot inséré au milieu
  ///       d'un token plutôt qu'ajouté en tant que mot séparé (« Accor Arena » / « Accorhotels
  ///       Arena ») </ol>
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les salles sont considérées identiques
  public static boolean estDoublonSalle(String a, String b) {
    if (estDoublonTexte(
        a, b, SEUIL_SIMILARITE_SALLE, LONGUEUR_MIN_CONTAINMENT_SALLE, RATIO_MIN_CONTAINMENT_SALLE))
      return true;
    if (a.replace(" ", "").equals(b.replace(" ", ""))) return true;

    Set<String> tokA = tokensSignificatifs(a);
    Set<String> tokB = tokensSignificatifs(b);
    Set<String> specA = tokensSpecifiques(a);
    Set<String> specB = tokensSpecifiques(b);

    Set<String> intersectionSpec = new HashSet<>(specA);
    intersectionSpec.retainAll(specB);
    Set<String> unionSpec = new HashSet<>(specA);
    unionSpec.addAll(specB);
    double jaccardSpec =
        unionSpec.isEmpty() ? 0.0 : (double) intersectionSpec.size() / unionSpec.size();

    // Au moins un token spécifique en commun requis — sans quoi deux noms entièrement génériques
    // (déjà couverts par l'égalité stricte ci-dessus) ou sans mot distinctif partagé ne doivent
    // jamais être déclarés doublons sur ce seul critère.
    if (!unionSpec.isEmpty() && jaccardSpec > SEUIL_JACCARD_SALLE) return true;

    // Pivot : requiert ≥ 2 tokens dans chaque nom pour éviter les faux positifs de type
    // "galaxie" (1 token) vs "le galaxie amneville".
    if (tokA.size() >= 2
        && tokB.size() >= 2
        && jaccardSpec > SEUIL_JACCARD_PIVOT
        && intersectionSpec.stream().anyMatch(t -> t.length() >= LONGUEUR_MIN_PIVOT)) {
      return true;
    }

    // Containment partiel : garde-fou sur le nombre de tokens SPÉCIFIQUES (pas bruts) de chaque
    // côté — sans lui, un nom réduit à un unique token spécifique après retrait des mots-types
    // génériques ("Casino de Paris" → seul "paris" reste après retrait de "casino") correspond
    // trivialement à n'importe quel autre nom partageant ce token comme simple nom de ville
    // ("Théâtre Rive Gauche Paris 14", "Cathédrale Américaine Paris 08"...), sans aucun rapport
    // avec le lieu recherché.
    if (specA.size() < 2 || specB.size() < 2) return false;
    Set<String> specCourt = specA.size() <= specB.size() ? specA : specB;
    Set<String> specLong = specA.size() <= specB.size() ? specB : specA;
    return estContainmentTokensPartiel(specCourt, specLong);
  }
}
