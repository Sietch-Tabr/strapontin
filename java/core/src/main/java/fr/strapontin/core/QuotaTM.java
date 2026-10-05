/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.time.Instant;

/**
 * Quota d'API Ticketmaster extrait des headers HTTP Rate-
 * Limit-* (détail : CLAUDE.md, Jobs background), champs
 * auto-descriptifs.
 * @since 2.2.3
 * @author Kwaite
 */
public record QuotaTM(int dispo, int total, long reset, String misAJour) {

  /**
   * Quota en vigueur à un instant : entièrement disponible une fois la remise
   * à zéro passée, sans attendre le prochain appel Ticketmaster.
   * @param maintenant instant de lecture
   * @return ce quota, le quota renouvelé, ou null si la remise à zéro est
   *     passée sans total connu (seul un appel Ticketmaster le révélera)
   * @since 3.0.2
   */
  public QuotaTM enVigueur(final Instant maintenant) {
    if (reset <= 0 || reset > maintenant.toEpochMilli()) {
      return this;
    }
    return total > 0 ? new QuotaTM(total, total, 0L, Instant.ofEpochMilli(reset).toString()) : null;
  }
}
