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

  // Un mot qui perd son article collé garde au moins cinq lettres (« lessaion » → « essaion »).
  private static final int LONGUEUR_MIN_APRES_ARTICLE = 5;

  private static final Pattern ARTICLE_ELIDE = Pattern.compile("\\b([a-z]) (?=[a-z])");

  // Mots de liaison de trois lettres, que tokensSignificatifs conserve.
  private static final Set<String> MOTS_DE_LIAISON = Set.of("des", "les", "aux", "sur", "une");

  // Activités accolées au nom par une billetterie (« Larchipel, théâtre,
  // cinéma ») : elles décrivent le lieu sans en désigner un autre.
  private static final Set<String> MOTS_D_ACTIVITE =
      Set.of("cinema", "musique", "musiques", "danse", "spectacle", "spectacles", "concerts");

  // Mots d'adressage postal que certaines billetteries accolent au nom d'un lieu.
  private static final Set<String> MOTS_SANS_PORTEE = Set.of("cedex");

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
   * Variante stricte pour deux lieux proches : mots du nom seulement, sans
   * similarité de caractères ni pivot (Élysée Montmartre / Montmartre-Galabru).
   * @param nomNormA premier nom normalisé
   * @param nomNormB second nom normalisé
   * @param contexteLieu villes et nom connus du lieu, mots sans portée ici
   * @return {@code true} si les mots désignent sans ambiguïté le même lieu
   */
  public static boolean designentFortementLeMemeLieu(
      final String nomNormA, final String nomNormB, final String contexteLieu) {
    if (designentSansDetacherLArticle(nomNormA, nomNormB, contexteLieu)) {
      return true;
    }
    final String detacheA = detacherArticleColle(nomNormA, nomNormB);
    final String detacheB = detacherArticleColle(nomNormB, nomNormA);
    return (!detacheA.equals(nomNormA) || !detacheB.equals(nomNormB))
        && designentSansDetacherLArticle(detacheA, detacheB, contexteLieu);
  }

  // « lessaion » chez Eventim, « essaion » chez Ticketmaster : l'article élidé collé à un mot
  // que l'autre nom porte seul se détache. Un reste de moins de cinq lettres reste un mot.
  private static String detacherArticleColle(final String nomNorm, final String nomNormAutre) {
    final Set<String> motsAutre = new HashSet<>(Arrays.asList(nomNormAutre.split(" ")));
    final StringBuilder resultat = new StringBuilder();
    for (final String mot : nomNorm.split(" ")) {
      final boolean articleColle =
          mot.length() > LONGUEUR_MIN_APRES_ARTICLE
              && (mot.charAt(0) == 'l' || mot.charAt(0) == 'd')
              && !motsAutre.contains(mot)
              && motsAutre.contains(mot.substring(1));
      if (resultat.length() > 0) {
        resultat.append(' ');
      }
      resultat.append(articleColle ? mot.substring(1) : mot);
    }
    return resultat.toString();
  }

  private static boolean designentSansDetacherLArticle(
      final String nomNormA, final String nomNormB, final String contexteLieu) {
    return CorrespondanceSalle.estDoublonSalleParMots(nomNormA, nomNormB)
        || memeCoeurDeNom(nomNormA, nomNormB, contexteLieu)
        || estSigleDe(nomNormA, nomNormB)
        || nomComplete(nomNormA, nomNormB, contexteLieu)
        || nomComplete(fusionnerApostropheElidee(nomNormA), nomNormB, contexteLieu)
        || nomComplete(nomNormA, fusionnerApostropheElidee(nomNormB), contexteLieu)
        || nomComplete(
            fusionnerArticlesElides(nomNormA), fusionnerArticlesElides(nomNormB), contexteLieu);
  }

  // « Théâtre des Cordeliers » / « Les Cordeliers Romans sur Isère » :
  // le mot distinctif du plus court figure dans l'autre, qui n'ajoute que
  // la ville ou des mots sans portée. Un nom qui n'a d'autre mot
  // distinctif que sa ville ne désigne aucun lieu précis.
  private static boolean memeCoeurDeNom(
      final String nomNormA, final String nomNormB, final String contexteLieu) {
    final Set<String> contexte =
        contexteLieu == null
            ? Set.of()
            : CorrespondanceSalle.tokensSignificatifs(Normalisation.normaliserTexte(contexteLieu));
    final Set<String> motsA = motsDistinctifs(nomNormA);
    final Set<String> motsB = motsDistinctifs(nomNormB);
    final Set<String> court = motsA.size() <= motsB.size() ? motsA : motsB;
    final Set<String> plusLong = motsA.size() <= motsB.size() ? motsB : motsA;
    if (court.isEmpty() || !plusLong.containsAll(court) || contexte.containsAll(court)) {
      return false;
    }
    final Set<String> motsEnPlus = new HashSet<>(plusLong);
    motsEnPlus.removeAll(court);
    return contexte.containsAll(motsEnPlus);
  }

  private static Set<String> motsDistinctifs(final String nomNorm) {
    final Set<String> mots = new HashSet<>(CorrespondanceSalle.tokensSignificatifs(nomNorm));
    mots.removeAll(CorrespondanceSalle.MOTS_GENERIQUES_SALLE);
    mots.removeAll(MOTS_DE_LIAISON);
    mots.removeAll(MOTS_D_ACTIVITE);
    mots.removeAll(MOTS_SANS_PORTEE);
    return mots;
  }

  // Contenu dans l'autre, complété de mots sans portée seulement (villes, nom de
  // la salle, génériques) : « theatre de poche montparnasse » est un autre lieu.
  private static boolean nomComplete(
      final String nomNormA, final String nomNormB, final String contexteLieu) {
    if (!nomContenuDansLAutre(nomNormA, nomNormB)) {
      return false;
    }
    final Set<String> motsEnPlus = new HashSet<>(CorrespondanceSalle.tokensSignificatifs(nomNormA));
    motsEnPlus.addAll(CorrespondanceSalle.tokensSignificatifs(nomNormB));
    final String court = nomNormA.length() <= nomNormB.length() ? nomNormA : nomNormB;
    motsEnPlus.removeAll(CorrespondanceSalle.tokensSignificatifs(court));
    return sansMotDistinctif(String.join(" ", motsEnPlus), contexteLieu);
  }

  /**
   * Un nom réduit à des mots génériques et à sa ville ne désigne aucun lieu
   * précis (« palais des congres la baule escoublac » pour l'Atlantia).
   * @param nomNorm nom normalisé
   * @param ville ville du lieu, null si inconnue
   * @return {@code true} si aucun mot distinctif ne reste
   * @since 3.0.2
   */
  public static boolean sansMotDistinctif(final String nomNorm, final String ville) {
    final Set<String> mots = new HashSet<>(CorrespondanceSalle.tokensSignificatifs(nomNorm));
    mots.removeAll(CorrespondanceSalle.MOTS_GENERIQUES_SALLE);
    mots.removeAll(MOTS_DE_LIAISON);
    mots.removeAll(MOTS_D_ACTIVITE);
    mots.removeAll(MOTS_SANS_PORTEE);
    if (ville != null) {
      mots.removeAll(CorrespondanceSalle.tokensSignificatifs(Normalisation.normaliserTexte(ville)));
    }
    return mots.isEmpty();
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
        || contientToken(fusionnerApostropheElidee(nomNormB), nomNormA)
        || nomContenuDansLAutre(
            fusionnerArticlesElides(nomNormA), fusionnerArticlesElides(nomNormB));
  }

  // Au milieu du nom aussi : « le palais d auron » / « le palais dauron bourges »,
  // « salle de l etoile » / « salle de letoile chateaurenard ».
  private static String fusionnerArticlesElides(final String nomNorm) {
    return ARTICLE_ELIDE.matcher(nomNorm).replaceAll("$1");
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
