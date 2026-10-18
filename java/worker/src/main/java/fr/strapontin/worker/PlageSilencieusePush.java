/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Plage horaire pendant laquelle aucune notification push n'est envoyée : de
 * 22 h à 7 h, heure de Paris. Les envois retenus partent à 7 h.
 * @since 3.0.6
 * @author Kwaite
 */
final class PlageSilencieusePush {

  /** Fuseau de référence des utilisateurs (heure d'été et d'hiver comprises). */
  /* package-private : partagé avec les planificateurs et les tests (même package). */
  static final ZoneId FUSEAU = ZoneId.of("Europe/Paris");

  private static final int HEURE_DEBUT = 22;
  private static final int HEURE_FIN = 7;

  private PlageSilencieusePush() {}

  /**
   * @param instant instant d'envoi envisagé
   * @return {@code true} entre 22 h (inclus) et 7 h (exclu), heure de Paris
   */
  /* package-private : appelé par NotificationPushDigestService (même package). */
  static boolean estActive(final Instant instant) {
    final int heure = ZonedDateTime.ofInstant(instant, FUSEAU).getHour();
    return heure >= HEURE_DEBUT || heure < HEURE_FIN;
  }
}
