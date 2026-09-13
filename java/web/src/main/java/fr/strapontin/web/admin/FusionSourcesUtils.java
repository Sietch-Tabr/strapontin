/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.Map;

/**
 * Utilitaires partagés entre {@link ReferentielSallesService} et {@link
 * ReferentielArtistesService} pour la fusion de doublons — jusqu'ici domicilié dans {@code
 * ReferentielSallesService} seul, bien que déjà réutilisé tel quel côté artistes.
 *
 * @since 2.8.7
 * @author Kwaite
 */
final class FusionSourcesUtils {

  private FusionSourcesUtils() {}

  /**
   * Compare la santé de deux lignes {@code salle_sources} (ou {@code artiste_sources}) en conflit
   * lors d'une fusion : une ligne non suspecte l'emporte toujours sur une ligne suspecte, à
   * égalité de statut la ligne avec le moins d'échecs consécutifs l'emporte.
   *
   * @param conflit ligne agrégée contenant {@code suspect_source}/{@code suspect_cible} et {@code
   *   sante_source}/{@code sante_cible}
   * @return {@code true} si la ligne côté source doit être conservée
   */
  /*
   * package-private : appelé par
   * ReferentielSallesService/ReferentielArtistesService (même package).
   */
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
