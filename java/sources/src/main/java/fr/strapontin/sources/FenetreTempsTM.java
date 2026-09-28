/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Fenêtre temporelle (aujourd'hui → +6 mois) au format API TM
 * ({@code startDateTime}/{@code endDateTime}) — extrait de {@link
 * TicketmasterSource}.
 * @since 2.9.1
 * @author Kwaite
 */
record FenetreTempsTM(String debut, String fin) {

  /* package-private : appelé par TicketmasterSource/TicketmasterRegionFetcher
   * (même package). */
  static FenetreTempsTM obtenirFenetreTemps() {
    final Instant maintenant = Instant.now();
    final Instant dans6Mois = maintenant.plus(6L * 30, ChronoUnit.DAYS);
    final DateTimeFormatter fmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    return new FenetreTempsTM(fmt.format(maintenant), fmt.format(dans6Mois));
  }

  /**
   * Dernière date entièrement couverte par la fenêtre (la borne de fin tombe
   * en cours de journée, ce jour-là n'est que partiellement couvert).
   * @return date de fin de couverture (UTC)
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterRegionFetcher
   * (même package). */
  static LocalDate derniereDateCouverte() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(6L * 30 - 1);
  }

  /**
   * Borne {@code startDateTime} au début (UTC) d'une date donnée.
   * @param date date de début
   * @return horodatage au format TM
   */
  /* package-private : appelé par TicketmasterRegionFetcher (même package). */
  static String debutDeJournee(final LocalDate date) {
    return date + "T00:00:00Z";
  }
}
