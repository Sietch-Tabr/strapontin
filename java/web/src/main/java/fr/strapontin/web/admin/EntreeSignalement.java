/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/**
 * Une entrée de la vue unifiée des signalements (matching et invariants de données).
 *
 * @param categorie mécanisme d'origine (nouveau, necessite_intervention, contamination_salle,
 *     doublon_salle), qui détermine l'action possible
 * @param id identifiant du signalement dans son mécanisme
 * @param type type de signalement, décrit par {@link CatalogueSignalements}
 * @param entite entité concernée
 * @param confiance niveau de confiance
 * @param detecteLe date de détection, null si le mécanisme ne la conserve pas
 * @param details détails structurés (JSON), peut être null
 * @since 2.9.0
 * @author Kwaite
 */
public record EntreeSignalement(
    String categorie,
    String id,
    String type,
    EntiteSignalee entite,
    String confiance,
    Instant detecteLe,
    String details) {

  /**
   * Entité concernée par un signalement.
   *
   * @param type salle, artiste, utilisateur, identifiant ou invariant (anomalie globale)
   * @param id identifiant de l'entité
   * @param nom nom lisible, null pour une anomalie globale
   */
  public record EntiteSignalee(String type, String id, String nom) {}
}
