/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Utilitaire de formatage des dates en français.
 *
 * @since 2.0.0
 * @author Kwaite
 */
public final class Dates {

  private static final ZoneId ZONE_FR = ZoneId.of("Europe/Paris");

  private static final DateTimeFormatter FORMAT_COURT =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_COURT_ANNEE =
      DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_LONG =
      DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
  private static final DateTimeFormatter FORMAT_HEURE =
      DateTimeFormatter.ofPattern("EEE d MMM HH'h'mm", Locale.FRENCH);

  private Dates() {}

  /**
   * Formate une date ISO-8601 ({@code yyyy-MM-dd}) en français.
   *
   * Pour le format court, l'année est ajoutée si la date n'est pas dans l'année courante
   * (ex. "jeu. 8 avr. 2027" vs "jeu. 8 avr.").
   *
   * @param dateIso  la date au format {@code yyyy-MM-dd}, ou {@code null}
   * @param longue   {@code true} pour le format long (ex. "lundi 15 septembre 2026"),
   *                 {@code false} pour le format court (ex. "lun. 15 sept.")
   * @return la date formatée, ou chaîne vide si l'entrée est {@code null} ou invalide
   */
  public static String formater(final String dateIso, final boolean longue) {
    if (dateIso == null || dateIso.isBlank()) {
      return "";
    }
    try {
      final LocalDate date = LocalDate.parse(dateIso);
      if (longue) {
        return date.format(FORMAT_LONG);
      }
      final boolean autreAnnee = date.getYear() != LocalDate.now().getYear();
      return date.format(autreAnnee ? FORMAT_COURT_ANNEE : FORMAT_COURT);
    } catch (Exception e) {
      return dateIso;
    }
  }

  /**
   * Formate une date ISO-8601 avec une heure optionnelle en français.
   *
   * L'heure est affichée sous la forme "20h" (minutes omises si {@code 00}) ou "20h30".
   *
   * @param dateIso  date au format {@code yyyy-MM-dd}, ou {@code null}
   * @param heureStr heure au format {@code HH:mm:ss} ou {@code HH:mm}, ou {@code null}
   * @return la date (et l'heure) formatée, ex. "mer. 4 juin 20h30"
   */
  public static String formaterAvecHeure(final String dateIso, final String heureStr) {
    if (dateIso == null || dateIso.isBlank()) {
      return "";
    }
    try {
      final LocalDate date = LocalDate.parse(dateIso);
      final boolean autreAnnee = date.getYear() != LocalDate.now().getYear();
      final String datePart = date.format(autreAnnee ? FORMAT_COURT_ANNEE : FORMAT_COURT);
      if (heureStr == null || heureStr.isBlank()) {
        return datePart;
      }
      final String[] parts = heureStr.split(":");
      final int h = Integer.parseInt(parts[0]);
      final int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      return datePart + " " + h + "h" + (m == 0 ? "" : String.format("%02d", m));
    } catch (Exception e) {
      return dateIso;
    }
  }

  /**
   * Formate un epoch en millisecondes en "dim. 22 juin 21h05" (fuseau Europe/Paris).
   *
   * @param epochMs  timestamp en millisecondes
   * @return la date/heure formatée, ou chaîne vide si {@code epochMs <= 0}
   */
  public static String formaterTs(final long epochMs) {
    if (epochMs <= 0) {
      return "";
    }
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE_FR).format(FORMAT_HEURE);
  }

  /**
   * Formate un {@link Instant} en "dim. 22 juin 21h05" (fuseau Europe/Paris).
   *
   * @param instant  l'instant à formater, ou {@code null}
   * @return la date/heure formatée, ou chaîne vide si {@code null}
   */
  public static String formaterInstant(final Instant instant) {
    if (instant == null) {
      return "";
    }
    return LocalDateTime.ofInstant(instant, ZONE_FR).format(FORMAT_HEURE);
  }

  /**
   * Formate une chaîne ISO-8601 (ex. {@code misAJour} de {@code QuotaTM}) en "dim. 22 juin 21h05".
   *
   * @param isoInstant  instant au format ISO-8601, ou {@code null}
   * @return la date/heure formatée, ou chaîne vide si {@code null} ou invalide
   */
  public static String formaterInstantStr(final String isoInstant) {
    if (isoInstant == null || isoInstant.isBlank()) {
      return "";
    }
    try {
      return formaterInstant(Instant.parse(isoInstant));
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Formate un epoch en secondes (ex. header TM {@code Rate-Limit-Reset}) en "dim. 22 juin 21h05".
   *
   * @param epochSec  timestamp en secondes
   * @return la date/heure formatée, ou chaîne vide si {@code epochSec <= 0}
   */
  public static String formaterEpochSec(final long epochSec) {
    if (epochSec <= 0) {
      return "";
    }
    return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZONE_FR).format(FORMAT_HEURE);
  }
}
