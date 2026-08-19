/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/// Utilitaires de manipulation de chaînes partagés entre modules.
///
/// @since 2.2.3
/// @author Kwaite
public final class UtilitairesChaine {

  private UtilitairesChaine() {
    // Classe utilitaire — pas d'instanciation
  }

  /// Échappe une chaîne pour insertion sûre dans une valeur JSON construite à la main.
  ///
  /// Échappe les antislashs, guillemets doubles et retours à la ligne. Destiné aux petits payloads
  /// JSON assemblés sans dépendance Jackson (notifications push).
  ///
  /// @param s la chaîne à échapper (non null)
  /// @return la chaîne échappée
  /// @since 2.2.3
  public static String echapperJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  /// Vérifie si la plus courte des deux chaînes est incluse dans la plus longue, avec un ratio de
  /// longueur minimal — critère de repli couvrant les troncatures et variantes partielles entre
  /// deux formes d'un même nom (partagé par {@link CorrespondanceSalle} et {@link
  /// CorrespondanceArtiste}).
  ///
  /// @param a première chaîne
  /// @param b deuxième chaîne
  /// @param longueurMinContainment longueur minimale de la chaîne la plus courte pour appliquer le
  ///   critère
  /// @param ratioMinContainment ratio minimal longueur(court)/longueur(long) requis
  /// @return {@code true} si la plus courte est contenue dans la plus longue et que le ratio de
  ///   longueur est respecté
  /// @since 2.7.11
  public static boolean estContainmentRatio(
      String a, String b, int longueurMinContainment, double ratioMinContainment) {
    String court = a.length() <= b.length() ? a : b;
    String plusLong = a.length() <= b.length() ? b : a;
    double ratio = (double) court.length() / plusLong.length();
    return court.length() >= longueurMinContainment
        && ratio >= ratioMinContainment
        && plusLong.contains(court);
  }
}
