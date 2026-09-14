/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Utilitaires de manipulation de chaînes partagés entre modules.
 *
 * @since 2.2.3
 * @author Kwaite
 */
public final class UtilitairesChaine {

  private UtilitairesChaine() {
    // Classe utilitaire — pas d'instanciation
  }

  /**
   * Échappe une chaîne pour insertion sûre dans une valeur JSON
   * construite à la main (petits payloads sans Jackson, ex.
   * notifications push).
   * @param texte la chaîne à échapper (non null)
   * @return la chaîne échappée
   * @since 2.2.3
   */
  public static String echapperJson(final String texte) {
    return texte
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /**
   * Échappe une chaîne pour insertion sûre dans du HTML —
   * partagé entre {@code EmailTemplates} et
   * {@code NotificationDigestService}.
   * @param valeur la valeur à échapper ({@code null} → chaîne vide)
   * @return la chaîne échappée
   * @since 2.8.7
   */
  public static String echapperHtml(final Object valeur) {
    if (valeur == null) {
      return "";
    }
    return String.valueOf(valeur)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /**
   * Containment avec ratio de longueur minimal (détail :
   * CLAUDE.md, Dédoublonnage inter-sources).
   * @param longueurMinContainment longueur min. de la plus courte
   * @param ratioMinContainment ratio minimal longueur(court)/long
   * @return {@code true} si contenue et ratio respecté
   * @since 2.7.11
   */
  public static boolean estContainmentRatio(
      final String nomA,
      final String nomB,
      final int longueurMinContainment,
      final double ratioMinContainment) {
    final String court = nomA.length() <= nomB.length() ? nomA : nomB;
    final String plusLong = nomA.length() <= nomB.length() ? nomB : nomA;
    final double ratio = (double) court.length() / plusLong.length();
    return court.length() >= longueurMinContainment
        && ratio >= ratioMinContainment
        && plusLong.contains(court);
  }

  /**
   * Égalité de deux chaînes une fois tous les espaces retirés
   * (détail : CLAUDE.md, Dédoublonnage inter-sources).
   * @return {@code true} si identiques sans espaces
   * @since 2.9.1
   */
  public static boolean estEgaliteSansEspaces(final String nomA, final String nomB) {
    return nomA.replace(" ", "").equals(nomB.replace(" ", ""));
  }
}
