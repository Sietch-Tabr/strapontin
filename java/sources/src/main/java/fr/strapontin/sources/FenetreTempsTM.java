/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.time.Instant;
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
}
