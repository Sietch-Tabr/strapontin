/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Distance orthodromique (formule de Haversine) entre deux points GPS —
 * algorithme partagé entre worker (fusion de salles) et sources
 * (validation d'un candidat d'enrichissement Wikidata).
 * @since 3.0.0
 * @author Kwaite
 */
public final class DistanceGeo {

  private static final double RAYON_TERRE_METRES = 6_371_000;

  private DistanceGeo() {}

  /**
   * @param lat1 latitude du premier point
   * @param lon1 longitude du premier point
   * @param lat2 latitude du second point
   * @param lon2 longitude du second point
   * @return distance en mètres entre les deux points
   */
  public static double metres(
      final double lat1, final double lon1, final double lat2, final double lon2) {
    final double phi1 = Math.toRadians(lat1);
    final double phi2 = Math.toRadians(lat2);
    final double deltaPhi = Math.toRadians(lat2 - lat1);
    final double deltaLambda = Math.toRadians(lon2 - lon1);
    final double termeHaversine =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
            + Math.cos(phi1)
                * Math.cos(phi2)
                * Math.sin(deltaLambda / 2)
                * Math.sin(deltaLambda / 2);
    final double distanceAngulaire =
        2 * Math.atan2(Math.sqrt(termeHaversine), Math.sqrt(1 - termeHaversine));
    return RAYON_TERRE_METRES * distanceAngulaire;
  }

  /**
   * Permissif si une coordonnée est inconnue : retourne {@code false},
   * jamais un rejet basé sur une absence de donnée.
   * @param seuilMetres distance maximale, en mètres, pour être jugés proches
   * @return {@code true} si les deux points sont connus et à moins de
   *     {@code seuilMetres} l'un de l'autre
   */
  public static boolean connuesEtProches(
      final Double latA,
      final Double lonA,
      final Double latB,
      final Double lonB,
      final double seuilMetres) {
    if (latA == null || lonA == null || latB == null || lonB == null) {
      return false;
    }
    return metres(latA, lonA, latB, lonB) <= seuilMetres;
  }
}
