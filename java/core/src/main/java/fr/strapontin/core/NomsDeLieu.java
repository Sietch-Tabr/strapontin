/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Set;

/**
 * Noms de lieux publiés par les billetteries : deux noms désignent-ils un
 * même lieu (doublon, nom complété d'une ville, sigle, apostrophe élidée).
 * @since 3.0.2
 * @author Kwaite
 */
public final class NomsDeLieu {

  // Longueur minimale du sigle (sans espace) pour éviter un match trivial sur
  // une seule lettre.
  private static final int LONGUEUR_MIN_SIGLE = 2;

  // Longueur minimale pour éviter un containment trivial sur un nom déjà très
  // court.
  private static final int LONGUEUR_MIN_CONTAINMENT_DOUBLON = 4;

  private NomsDeLieu() {}

  /**
   * Deux noms normalisés peuvent-ils désigner un même lieu : doublon de nom,
   * nom complété d'une ville, sigle ou apostrophe élidée.
   * @param nomNormA premier nom normalisé
   * @param nomNormB second nom normalisé
   * @return {@code true} si les deux noms peuvent désigner le même lieu
   */
  public static boolean designentLeMemeLieu(final String nomNormA, final String nomNormB) {
    return CorrespondanceSalle.estDoublonSalle(nomNormA, nomNormB)
        || variantesDUnMemeNom(nomNormA, nomNormB);
  }

  /**
   * Variante stricte pour deux lieux déjà proches : seuls les mots du nom
   * comptent, ni similarité de caractères ni mot pivot, qui rapprochent deux
   * lieux voisins (« elysee montmartre » / « theatre montmartre galabru »).
   * @param nomNormA premier nom normalisé
   * @param nomNormB second nom normalisé
   * @return {@code true} si les mots désignent sans ambiguïté le même lieu
   */
  public static boolean designentFortementLeMemeLieu(final String nomNormA, final String nomNormB) {
    return CorrespondanceSalle.estDoublonSalleParMots(nomNormA, nomNormB)
        || variantesDUnMemeNom(nomNormA, nomNormB);
  }

  private static boolean variantesDUnMemeNom(final String nomNormA, final String nomNormB) {
    return nomContenuDansLAutre(nomNormA, nomNormB)
        || estSigleDe(nomNormA, nomNormB)
        || estApostropheElideeContenue(nomNormA, nomNormB);
  }

  /**
   * Généralise {@link #nomContenuDansLAutre} à un nom commençant par une
   * apostrophe élidée (ex. "L'Astrada" → {@code nom_norm} "l astrada") face
   * à un identifiant source collant le mot sans apostrophe (ex. "lastrada
   * marciac") — sans fusion, le token d'une seule lettre "l" est filtré,
   * aucun rapport n'est détecté malgré l'évidence.
   */
  private static boolean estApostropheElideeContenue(final String nomNormA, final String nomNormB) {
    return contientToken(fusionnerApostropheElidee(nomNormA), nomNormB)
        || contientToken(fusionnerApostropheElidee(nomNormB), nomNormA);
  }

  // article élidé fusionné avec le mot suivant ("l astrada" -> "lastrada") —
  // le token d'une seule lettre est systématiquement filtré en amont.
  private static String fusionnerApostropheElidee(final String nomNorm) {
    return nomNorm.replaceFirst("^([a-z]) ", "$1");
  }

  private static boolean contientToken(final String tokenFusionne, final String nomNormAutre) {
    return !tokenFusionne.isEmpty()
        && !tokenFusionne.contains(" ")
        && tokenFusionne.length() >= LONGUEUR_MIN_CONTAINMENT_DOUBLON
        && nomNormAutre.contains(tokenFusionne);
  }

  /**
   * Vérifie si la plus courte des deux chaînes est le sigle de la plus longue —
   * les initiales de chaque mot de la plus longue, concaténées sans espace,
   * égalent exactement la plus courte sans espace (ex. "ed n" pour "espace
   * dollfus noack" → initiales e, d, n).
   */
  private static boolean estSigleDe(final String nomA, final String nomB) {
    final String court = nomA.length() <= nomB.length() ? nomA : nomB;
    final String sigleObserve = court.replace(" ", "");
    if (sigleObserve.length() < LONGUEUR_MIN_SIGLE) {
      return false;
    }

    final String plusLong = nomA.length() <= nomB.length() ? nomB : nomA;
    final String[] mots = plusLong.split("\\s+");
    if (mots.length != sigleObserve.length()) {
      return false;
    }

    final StringBuilder initiales = new StringBuilder();
    for (final String mot : mots) {
      if (mot.isEmpty()) {
        return false;
      }
      initiales.append(mot.charAt(0));
    }
    return initiales.toString().equals(sigleObserve);
  }

  // Containment par token complet plutôt que sous-chaîne brute : un
  // containment de caractères validait à tort un article court ("les")
  // trivialement présent dans un token du nom long ("lessaion") sans aucun
  // rapport sémantique réel — même classe de faux positif que
  // CorrespondanceSalle#estContainmentTokensPartiel.
  /**
   * @param nomNormA premier nom normalisé
   * @param nomNormB second nom normalisé
   * @return {@code true} si tous les mots significatifs du plus court
   *     figurent dans le plus long
   */
  public static boolean nomContenuDansLAutre(final String nomNormA, final String nomNormB) {
    final String court = nomNormA.length() <= nomNormB.length() ? nomNormA : nomNormB;
    if (court.length() < LONGUEUR_MIN_CONTAINMENT_DOUBLON) {
      return false;
    }
    final String plusLong = nomNormA.length() <= nomNormB.length() ? nomNormB : nomNormA;
    final Set<String> tokensCourt = CorrespondanceSalle.tokensSignificatifs(court);
    final Set<String> tokensLong = CorrespondanceSalle.tokensSignificatifs(plusLong);
    return !tokensCourt.isEmpty() && tokensLong.containsAll(tokensCourt);
  }
}
