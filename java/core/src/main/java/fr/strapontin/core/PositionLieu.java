/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.regex.Pattern;

/**
 * Position d'un lieu telle que publiée par une source (coordonnées et code postal), signal
 * d'identité indépendant de son nom.
 *
 * @param latitude latitude WGS84, null si inconnue
 * @param longitude longitude WGS84, null si inconnue
 * @param codePostal code postal français à 5 chiffres, null si inconnu ou invalide
 * @since 3.0.2
 * @author Kwaite
 */
public record PositionLieu(Double latitude, Double longitude, String codePostal) {

  /** Position entièrement inconnue. */
  public static final PositionLieu INCONNUE = new PositionLieu(null, null, null);

  private static final Pattern CODE_POSTAL = Pattern.compile("\\d{5}");
  private static final double LATITUDE_MAX = 90;
  private static final double LONGITUDE_MAX = 180;

  /** Écarte un code postal mal formé et des coordonnées hors bornes ou nulles (0,0). */
  public PositionLieu {
    codePostal =
        codePostal != null && CODE_POSTAL.matcher(codePostal.strip()).matches()
            ? codePostal.strip()
            : null;
    if (latitude == null
        || longitude == null
        || Math.abs(latitude) > LATITUDE_MAX
        || Math.abs(longitude) > LONGITUDE_MAX
        || (latitude == 0 && longitude == 0)) {
      latitude = null;
      longitude = null;
    }
  }

  /**
   * Construit une position depuis des valeurs publiées sous forme de texte ou de nombre.
   *
   * @param latitude latitude ({@link Number} ou texte décimal), peut être null
   * @param longitude longitude ({@link Number} ou texte décimal), peut être null
   * @param codePostal code postal, peut être null
   * @return la position, coordonnées nulles si illisibles
   */
  public static PositionLieu depuis(
      final Object latitude, final Object longitude, final Object codePostal) {
    return new PositionLieu(
        enDouble(latitude), enDouble(longitude), codePostal instanceof String cp ? cp : null);
  }

  /**
   * @return true si les coordonnées sont connues
   */
  public boolean estGeolocalisee() {
    return latitude != null && longitude != null;
  }

  private static Double enDouble(final Object valeur) {
    if (valeur instanceof Number nombre) {
      return nombre.doubleValue();
    }
    if (valeur instanceof String texte) {
      try {
        return Double.parseDouble(texte.strip());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
