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

  /// Découpe une chaîne normalisée en tokens significatifs (filtre les mots de moins de
  /// {@value #LONGUEUR_MIN_TOKEN_JACCARD} caractères — articles "le", "de", "la"…).
  ///
  /// @param s chaîne normalisée
  /// @return ensemble des tokens significatifs
  public static Set<String> tokensSignificatifs(String s) {
    Set<String> tokens = new HashSet<>(Arrays.asList(s.split("\\s+")));
    tokens.removeIf(t -> t.length() < LONGUEUR_MIN_TOKEN_JACCARD);
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

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// <p>Trois critères en union :
  /// <ol>
  ///   <li>Levenshtein ≥ 0.75 ou containment (via {@link Normalisation#similarite})</li>
  ///   <li>Jaccard tokens strict {@code > 0.5} — insensible à l'ordre des mots</li>
  ///   <li>Token pivot — token commun ≥ {@value #LONGUEUR_MIN_PIVOT} chars et Jaccard
  ///       {@code > }{@value #SEUIL_JACCARD_PIVOT}</li>
  /// </ol>
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les salles sont considérées identiques
  public static boolean estDoublonSalle(String a, String b) {
    if (estDoublonTexte(
        a, b, SEUIL_SIMILARITE_SALLE, LONGUEUR_MIN_CONTAINMENT_SALLE, RATIO_MIN_CONTAINMENT_SALLE))
      return true;
    Set<String> tokA = tokensSignificatifs(a);
    Set<String> tokB = tokensSignificatifs(b);
    Set<String> intersection = new HashSet<>(tokA);
    intersection.retainAll(tokB);
    Set<String> union = new HashSet<>(tokA);
    union.addAll(tokB);
    double jaccard =
        union.isEmpty()
            ? (tokA.isEmpty() && tokB.isEmpty() ? 1.0 : 0.0)
            : (double) intersection.size() / union.size();
    if (jaccard > SEUIL_JACCARD_SALLE) return true;
    // Critère pivot : requiert ≥ 2 tokens dans chaque nom pour éviter les faux positifs
    // de type "galaxie" (1 token) vs "le galaxie amneville".
    return tokA.size() >= 2
        && tokB.size() >= 2
        && jaccard > SEUIL_JACCARD_PIVOT
        && intersection.stream().anyMatch(t -> t.length() >= LONGUEUR_MIN_PIVOT);
  }
}
