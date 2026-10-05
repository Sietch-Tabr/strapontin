/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

/**
 * Condition SQL d'une salle sans contamination connue : un concert partagé
 * avec une salle mélangeant plusieurs lieux ne prouve pas qu'il s'agit du
 * même lieu ni du même spectacle.
 * @since 3.0.2
 * @author Kwaite
 */
final class SalleFiable {

  private SalleFiable() {}

  /**
   * @param alias alias SQL de la table {@code salles}
   * @return condition à placer dans une clause {@code WHERE}, précédée d'une
   *     espace (un bloc de texte retire celle qui suit son dernier mot)
   */
  /* package-private : partagé par les fusions automatiques du package. */
  static String condition(final String alias) {
    return " "
        + alias
        + ".contamination_signalee IS NULL AND NOT EXISTS (SELECT 1 FROM signalements_matching sm"
        + " WHERE sm.entite_id = "
        + alias
        + ".id AND sm.resolu_le IS NULL AND sm.type IN ('dispersion_sources_salle',"
        + " 'salle_multi_departements', 'salle_suivie_contaminee'))";
  }
}
