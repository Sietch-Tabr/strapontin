/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/// Coordonnées géographiques et rayon de couverture d'un département.
///
/// @param lat latitude WGS84 du centre du département
/// @param lng longitude WGS84 du centre du département
/// @param rayon demi-diagonale du bounding box OSM, en km
/// @since 2.0.0
/// @author Kwaite
public record CentreDept(double lat, double lng, int rayon) {}
