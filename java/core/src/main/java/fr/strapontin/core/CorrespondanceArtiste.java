/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Détermine si deux noms d'artiste normalisés désignent le même artiste.
 *
 * <p>Extrait de {@code worker/persister/ReconciliateurArtistes} (v2.5.7) vers {@code core} pour
 * être réutilisable par le module {@code web} (ajout d'artiste, v2.6.0) sans dépendance {@code web}
 * → {@code worker}.
 *
 * @since 2.6.0
 * @author Kwaite
 */
public final class CorrespondanceArtiste {

  // Seuil artiste : Jaro ≥ 0.95 + containment ratio court/long ≥ 0.65.
  // Jaro évite les faux positifs par préfixe commun ("julien dore"/"julien morel" Jaro=0.914 →
  // false) tout en couvrant les variantes sans espace ("krash karma"/"krashkarma" Jaro=0.97 →
  // true). Les troncatures TM ("aliz"/"alizee", "fawn"/"fawnn") passent par containment.
  private static final double SEUIL_JARO_ARTISTE = 0.95;
  private static final int LONGUEUR_MIN_CONTAINMENT_ARTISTE = 4;
  private static final double RATIO_MIN_CONTAINMENT_ARTISTE = 0.65;

  private CorrespondanceArtiste() {}

  /**
   * Vérifie si deux noms d'artiste normalisés désignent le même artiste.
   *
   * <p>Jaro ≥ 0.95 (meilleur que Levenshtein pour les noms propres courts) complété par un
   * containment avec ratio court/long ≥ 0.65 pour les troncatures TM ("aliz"/"alizee",
   * "fawn"/"fawnn") et les variations longues ("star academy"/"star academy 1"), par une égalité
   * sans espaces ({@link UtilitairesChaine#estEgaliteSansEspaces}) pour une contraction de nom
   * propre qu'une source omet ("N'Dour"/"NDour"), puis par un préfixe de mot complet pour un ajout
   * plus substantiel (co-tête d'affiche, suffixe de tournée) qui fait chuter le ratio de
   * containment sous le seuil.
   *
   * @param a premier nom normalisé
   * @param b deuxième nom normalisé
   * @return {@code true} si les artistes sont considérés identiques
   */
  public static boolean estDoublonArtiste(final String a, final String b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (a.equals(b)) {
      return true;
    }
    if (DistanceTexte.jaro(a, b) >= SEUIL_JARO_ARTISTE) {
      return true;
    }
    if (UtilitairesChaine.estContainmentRatio(
        a, b, LONGUEUR_MIN_CONTAINMENT_ARTISTE, RATIO_MIN_CONTAINMENT_ARTISTE)) {
      return true;
    }
    return UtilitairesChaine.estEgaliteSansEspaces(a, b) || estPrefixeDeMotComplet(a, b);
  }

  // Le nom court doit comporter au moins deux mots ("jay z", pas "mike" seul, préfixe trivial
  // d'un artiste sans rapport) et se couper sur un mot complet, sans exigence de ratio.
  private static boolean estPrefixeDeMotComplet(final String a, final String b) {
    final String court = a.length() <= b.length() ? a : b;
    final String plusLong = a.length() <= b.length() ? b : a;
    return court.contains(" ") && plusLong.startsWith(court + " ");
  }
}
