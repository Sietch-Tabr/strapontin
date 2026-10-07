/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.DistanceGeo;

/**
 * Validation de cohérence GPS d'un candidat de découverte Wikidata contre
 * la salle recherchée — extrait de {@link WikidataSalleDecouverte} (dette
 * de taille pure, aucun changement fonctionnel).
 * @since 3.0.0
 * @author Kwaite
 */
final class ValidationCoordonneesSalle {

  // Distance au-delà de laquelle un candidat Wikidata ne peut structurellement
  // pas être la salle déjà connue, même en cas de nom/ville/dept compatibles
  // — même seuil que FusionSallesClePorteeService (worker.persister), un
  // arrondissement différent peut partager dept et nom approximatif
  // (Alhambra Paris, ancien théâtre 11e vs salle de concert active 10e).
  private static final double DISTANCE_MAX_METRES = 500;

  private ValidationCoordonneesSalle() {}

  /**
   * Jamais de rejet sur une coordonnée inconnue. Sans position de la salle,
   * le candidat doit rester dans son département.
   * @return {@code true} si les positions sont distantes de plus de
   *     {@value #DISTANCE_MAX_METRES} m, ou le candidat hors du département
   */
  /* package-private : appelé uniquement par WikidataSalleDecouverte (même package). */
  static boolean incompatibles(
      final SalleAEnrichir salle, final Double coordLat, final Double coordLon) {
    if (coordLat == null || coordLon == null) {
      return false;
    }
    if (salle.latitude() == null || salle.longitude() == null) {
      return !PositionDepartement.plausible(salle.dept(), coordLat, coordLon);
    }
    return !DistanceGeo.connuesEtProches(
        salle.latitude(), salle.longitude(), coordLat, coordLon, DISTANCE_MAX_METRES);
  }
}
