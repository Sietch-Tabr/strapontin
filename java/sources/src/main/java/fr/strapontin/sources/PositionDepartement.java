/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.DistanceGeo;

/**
 * Plausibilité d'une position pour un département, d'après son centre et son
 * rayon ({@link CentresDepartement}).
 * @since 3.0.2
 * @author Kwaite
 */
public final class PositionDepartement {

  // Mesuré sur 412 salles positionnées par leurs lieux : toutes à moins de
  // 0,9 rayon de leur centre ; les positions fausses, au-delà de 2,7.
  private static final double MARGE_RAYON = 1.5;
  private static final double METRES_PAR_KM = 1000;

  private PositionDepartement() {}

  /**
   * @param dept département (code à deux caractères), null si inconnu
   * @param latitude latitude de la position
   * @param longitude longitude de la position
   * @return {@code true} si la position peut appartenir au département, ou si
   *     le département est inconnu
   */
  public static boolean plausible(
      final String dept, final double latitude, final double longitude) {
    final CentreDept centre = dept != null ? CentresDepartement.PAR_DEPT.get(dept) : null;
    return centre == null
        || DistanceGeo.metres(centre.lat(), centre.lng(), latitude, longitude)
            <= centre.rayon() * METRES_PAR_KM * MARGE_RAYON;
  }
}
