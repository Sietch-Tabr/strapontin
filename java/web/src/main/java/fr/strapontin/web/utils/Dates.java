/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/// Utilitaire de formatage des dates en français.
///
/// @since 2.0.0
/// @author Kwaite
public final class Dates {

  private static final DateTimeFormatter FORMAT_COURT =
      DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_LONG =
      DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);

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
}
