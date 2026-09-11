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

  /// Échappe une chaîne pour insertion sûre dans du HTML — partagé entre {@code
  /// web.email.EmailTemplates} et {@code worker.NotificationDigestService}, jusqu'ici dupliqué à
  /// l'identique dans les deux (gabarits d'email HTML).
  ///
  /// @param valeur la valeur à échapper, convertie en chaîne via {@link String#valueOf} ({@code
  ///   null} devient une chaîne vide)
  /// @return la chaîne échappée
  /// @since 2.8.7
  public static String echapperHtml(Object valeur) {
    if (valeur == null) return "";
    return String.valueOf(valeur)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
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

  /// Vérifie l'égalité de deux chaînes une fois tous les espaces retirés — couvre le cas où {@link
  /// Normalisation#normaliserTexte} remplace une apostrophe par une espace (ex. "N'Dour" → "n
  /// dour") alors qu'une source omet totalement cette apostrophe (ex. "NDour" → "ndour") : sans ce
  /// critère, les deux formes ne partagent aucun token commun. Partagé par {@link
  /// CorrespondanceSalle} et {@link CorrespondanceArtiste}.
  ///
  /// @param a première chaîne
  /// @param b deuxième chaîne
  /// @return {@code true} si les deux chaînes sont identiques une fois les espaces retirés
  /// @since 2.9.1
  public static boolean estEgaliteSansEspaces(String a, String b) {
    return a.replace(" ", "").equals(b.replace(" ", ""));
  }
}
