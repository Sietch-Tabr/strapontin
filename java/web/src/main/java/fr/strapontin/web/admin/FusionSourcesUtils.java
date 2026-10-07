/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.Map;

/**
 * Utilitaire de fusion de doublons d'artistes ({@link
 * ReferentielArtistesService}) : choix de l'identifiant source à garder.
 *
 * @since 2.8.7
 * @author Kwaite
 */
final class FusionSourcesUtils {

  private FusionSourcesUtils() {}

  /**
   * Compare la santé de deux lignes en conflit lors d'une fusion : non
   * suspecte l'emporte sur suspecte, sinon le moins d'échecs consécutifs.
   * @param conflit ligne agrégée {@code suspect_*}/{@code sante_*}
   * @return {@code true} si la ligne côté source doit être conservée
   */
  /* package-private : appelé par ReferentielArtistesService (même package). */
  static boolean laSourceEstPlusSaine(final Map<String, Object> conflit) {
    final boolean suspectSource = (boolean) conflit.get("suspect_source");
    final boolean suspectCible = (boolean) conflit.get("suspect_cible");
    if (suspectSource != suspectCible) {
      return !suspectSource;
    }
    final int santeSource = ((Number) conflit.get("sante_source")).intValue();
    final int santeCible = ((Number) conflit.get("sante_cible")).intValue();
    return santeSource <= santeCible;
  }
}
