/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/// Utilitaire de formatage des dates en français.
///
/// @since 2.0.0
/// @author Kwaite
public final class Dates {

  private static final ZoneId ZONE_FR = ZoneId.of("Europe/Paris");

  private static final DateTimeFormatter FORMAT_COURT =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_LONG =
      DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_HEURE =
      DateTimeFormatter.ofPattern("EEE d MMM HH'h'mm", Locale.FRENCH);

  private Dates() {}

  /// Formate une date ISO-8601 (`yyyy-MM-dd`) en français.
  ///
  /// @param dateIso  la date au format `yyyy-MM-dd`, ou {@code null}
  /// @param longue   {@code true} pour le format long (ex. "lundi 15 septembre 2026"),
  ///                 {@code false} pour le format court (ex. "lun. 15 sept. 2026")
  /// @return la date formatée, ou chaîne vide si l'entrée est {@code null} ou invalide
  public static String formater(String dateIso, boolean longue) {
    if (dateIso == null || dateIso.isBlank()) return "";
    try {
      LocalDate date = LocalDate.parse(dateIso);
      return date.format(longue ? FORMAT_LONG : FORMAT_COURT);
    } catch (Exception e) {
      return dateIso;
    }
  }

  /// Formate un epoch en millisecondes en "dim. 22 juin 21h05" (fuseau Europe/Paris).
  ///
  /// @param epochMs  timestamp en millisecondes
  /// @return la date/heure formatée, ou chaîne vide si {@code epochMs <= 0}
  public static String formaterTs(long epochMs) {
    if (epochMs <= 0) return "";
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE_FR).format(FORMAT_HEURE);
  }

  /// Formate un {@link Instant} en "dim. 22 juin 21h05" (fuseau Europe/Paris).
  ///
  /// @param instant  l'instant à formater, ou {@code null}
  /// @return la date/heure formatée, ou chaîne vide si {@code null}
  public static String formaterInstant(Instant instant) {
    if (instant == null) return "";
    return LocalDateTime.ofInstant(instant, ZONE_FR).format(FORMAT_HEURE);
  }

  /// Formate une chaîne ISO-8601 (ex. {@code misAJour} de {@code QuotaTM}) en "dim. 22 juin 21h05".
  ///
  /// @param isoInstant  instant au format ISO-8601, ou {@code null}
  /// @return la date/heure formatée, ou chaîne vide si {@code null} ou invalide
  public static String formaterInstantStr(String isoInstant) {
    if (isoInstant == null || isoInstant.isBlank()) return "";
    try {
      return formaterInstant(Instant.parse(isoInstant));
    } catch (Exception e) {
      return "";
    }
  }

  /// Formate un epoch en secondes (ex. header TM {@code Rate-Limit-Reset}) en "dim. 22 juin 21h05".
  ///
  /// @param epochSec  timestamp en secondes
  /// @return la date/heure formatée, ou chaîne vide si {@code epochSec <= 0}
  public static String formaterEpochSec(long epochSec) {
    if (epochSec <= 0) return "";
    return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZONE_FR).format(FORMAT_HEURE);
  }
}
