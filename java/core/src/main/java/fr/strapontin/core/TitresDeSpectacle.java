/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Reconnaît deux intitulés d'un même programme, qu'une billetterie décline en variantes
 * (« Les 4 Saisons de Vivaldi, Ave Maria et Célèbres Adagios » / « Extraits, Ave Maria »).
 * @since 3.1.4
 * @author Kwaite
 */
public final class TitresDeSpectacle {

  private static final int LONGUEUR_MIN_MOT = 4;
  private static final int MOTS_COMMUNS_MIN = 2;
  private static final double COUVERTURE_MIN = 0.5;
  // Mots qui reviennent dans des programmes sans rapport.
  private static final Set<String> MOTS_BANALS = Set.of("concert", "live", "paris", "avec", "tour");

  private TitresDeSpectacle() {}

  /**
   * @param titreA premier intitulé
   * @param titreB second intitulé
   * @return {@code true} si les deux intitulés partagent au moins deux mots significatifs, qui
   *     couvrent la moitié des mots du plus court
   */
  public static boolean sontVariantes(final String titreA, final String titreB) {
    final Set<String> motsA = mots(titreA);
    final Set<String> motsB = mots(titreB);
    if (motsA.isEmpty() || motsB.isEmpty()) {
      return false;
    }
    final Set<String> communs = new HashSet<>(motsA);
    communs.retainAll(motsB);
    return communs.size() >= MOTS_COMMUNS_MIN
        && (double) communs.size() / Math.min(motsA.size(), motsB.size()) >= COUVERTURE_MIN;
  }

  private static Set<String> mots(final String titre) {
    final Set<String> mots = new HashSet<>();
    if (titre == null) {
      return mots;
    }
    for (final String mot : Normalisation.normaliserTexte(titre).split("\\s+")) {
      if (mot.length() >= LONGUEUR_MIN_MOT && !MOTS_BANALS.contains(mot)) {
        mots.add(mot);
      }
    }
    return mots;
  }
}
