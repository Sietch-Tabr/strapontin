/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Détermine si deux noms d'artiste normalisés désignent le
 * même artiste (détail : CLAUDE.md, Dédoublonnage inter-sources).
 * @since 2.6.0
 * @author Kwaite
 */
public final class CorrespondanceArtiste {

  // Seuil artiste : Jaro ≥ 0.95 + containment ratio court/long ≥ 0.65. Jaro
  // évite les faux positifs par préfixe commun ("julien dore"/"julien morel"
  // Jaro=0.914 → false) tout en couvrant les variantes sans espace ("krash
  // karma"/"krashkarma" Jaro=0.97 → true). Les troncatures TM ("aliz"/"alizee",
  // "fawn"/"fawnn") passent par containment.
  private static final double SEUIL_JARO_ARTISTE = 0.95;
  private static final int LONGUEUR_MIN_CONTAINMENT_ARTISTE = 4;
  private static final double RATIO_MIN_CONTAINMENT_ARTISTE = 0.65;

  // Mots qu'une billetterie ajoute au nom de la tête d'affiche sans changer de
  // concert : première partie, invités, tournée, liaisons de co-plateau.
  private static final Set<String> MOTS_ACCESSOIRES_AFFICHE =
      Set.of(
          "1ere",
          "premiere",
          "partie",
          "support",
          "guest",
          "guests",
          "invite",
          "invites",
          "tour",
          "tournee",
          "en",
          "and",
          "feat",
          "featuring",
          "avec",
          "x",
          "presents");
  // Liaisons entre le nom d'un artiste et celui de la salle dans un titre.
  private static final Set<String> MOTS_LIAISON_SALLE =
      Set.of(
          "a", "au", "aux", "l", "la", "le", "les", "chez", "de", "du", "des", "d", "en", "at",
          "live", "concert");
  private static final Pattern ANNEE = Pattern.compile("(19|20)\\d{2}");
  private static final int LONGUEUR_MIN_MOT_AFFICHE = 3;

  private CorrespondanceArtiste() {}

  /**
   * Même affiche (« meryl » / « meryl 1ere partie ») : tous les mots de l'un
   * figurent dans l'autre. Trop large hors de deux événements de même salle,
   * date et heure.
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si l'un des noms est contenu, mot à mot, dans l'autre
   */
  public static boolean estMemeAffiche(final String nomA, final String nomB) {
    Objects.requireNonNull(nomA, "nomA");
    Objects.requireNonNull(nomB, "nomB");
    if (Hommage.estHommage(nomA) != Hommage.estHommage(nomB)) {
      return false;
    }
    final Set<String> motsA = motsSignificatifs(nomA);
    final Set<String> motsB = motsSignificatifs(nomB);
    final Set<String> court = motsA.size() <= motsB.size() ? motsA : motsB;
    final Set<String> plusLong = motsA.size() <= motsB.size() ? motsB : motsA;
    final boolean motDistinctif =
        court.stream().anyMatch(m -> m.length() >= LONGUEUR_MIN_MOT_AFFICHE);
    return motDistinctif && plusLong.containsAll(court);
  }

  private static Set<String> motsSignificatifs(final String nom) {
    final Set<String> mots = new HashSet<>();
    for (final String mot : nom.split(" ")) {
      if (!mot.isEmpty()
          && !MOTS_ACCESSOIRES_AFFICHE.contains(mot)
          && !ANNEE.matcher(mot).matches()) {
        mots.add(mot);
      }
    }
    return mots;
  }

  /**
   * Vérifie si deux noms d'artiste normalisés désignent le
   * même artiste (détail : CLAUDE.md, Dédoublonnage inter-sources).
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les artistes sont considérés identiques
   */
  public static boolean estDoublonArtiste(final String nomA, final String nomB) {
    Objects.requireNonNull(nomA, "nomA");
    Objects.requireNonNull(nomB, "nomB");
    if (nomA.equals(nomB)) {
      return true;
    }
    // Un hommage n'est jamais l'artiste qu'il imite, quelle que soit la
    // proximité des noms.
    if (Hommage.estHommage(nomA) != Hommage.estHommage(nomB)) {
      return false;
    }
    if (DistanceTexte.jaro(nomA, nomB) >= SEUIL_JARO_ARTISTE) {
      return true;
    }
    if (UtilitairesChaine.estContainmentRatio(
        nomA, nomB, LONGUEUR_MIN_CONTAINMENT_ARTISTE, RATIO_MIN_CONTAINMENT_ARTISTE)) {
      return true;
    }
    return UtilitairesChaine.estEgaliteSansEspaces(nomA, nomB)
        || estPrefixeDeMotComplet(nomA, nomB);
  }

  // Le nom court doit comporter au moins deux mots ("jay z", pas "mike" seul,
  // préfixe trivial d'un artiste sans rapport) et se couper sur un mot complet,
  // sans exigence de ratio.
  private static boolean estPrefixeDeMotComplet(final String nomA, final String nomB) {
    final String court = nomA.length() <= nomB.length() ? nomA : nomB;
    final String plusLong = nomA.length() <= nomB.length() ? nomB : nomA;
    return court.contains(" ") && plusLong.startsWith(court + " ");
  }

  /**
   * Un titre « nom + salle » n'est pas un autre artiste : les mots ajoutés, hors
   * liaisons, sont tous des mots du nom de la salle.
   * @param titre titre complet normalisé
   * @param nom nom d'artiste normalisé que le titre commencerait
   * @param salleNorm nom de la salle normalisé, peut être {@code null}
   * @return {@code true} si le titre est le nom suivi de la salle
   */
  public static boolean estNomSuiviDeLaSalle(
      final String titre, final String nom, final String salleNorm) {
    if (titre == null || nom == null || salleNorm == null || !titre.startsWith(nom + " ")) {
      return false;
    }
    final Set<String> motsSalle = Set.of(salleNorm.split("\\s+"));
    final List<String> motsAjoutes =
        Arrays.stream(titre.substring(nom.length() + 1).split("\\s+"))
            .filter(mot -> !mot.isEmpty() && !MOTS_LIAISON_SALLE.contains(mot))
            .toList();
    return !motsAjoutes.isEmpty() && motsSalle.containsAll(motsAjoutes);
  }
}
