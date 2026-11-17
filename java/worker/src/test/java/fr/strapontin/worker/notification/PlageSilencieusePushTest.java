/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Plage silencieuse du push : 22 h à 7 h, heure de Paris, été comme hiver.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class PlageSilencieusePushTest {

  @Test
  void estActive_avantVingtDeuxHeuresEte_retourneFalse() {
    // 19:59:59 UTC = 21:59:59 à Paris (UTC+2).
    assertFalse(PlageSilencieusePush.estActive(Instant.parse("2026-10-17T19:59:59Z")));
  }

  @Test
  void estActive_aVingtDeuxHeuresEte_retourneTrue() {
    assertTrue(PlageSilencieusePush.estActive(Instant.parse("2026-10-17T20:00:00Z")));
  }

  @Test
  void estActive_aSixHeuresCinquanteNeufEte_retourneTrue() {
    assertTrue(PlageSilencieusePush.estActive(Instant.parse("2026-10-18T04:59:59Z")));
  }

  @Test
  void estActive_aSeptHeuresEte_retourneFalse() {
    assertFalse(PlageSilencieusePush.estActive(Instant.parse("2026-10-18T05:00:00Z")));
  }

  @Test
  void estActive_aVingtDeuxHeuresHiver_retourneTrue() {
    // 21:00 UTC = 22:00 à Paris (UTC+1) : la plage suit l'heure légale.
    assertTrue(PlageSilencieusePush.estActive(Instant.parse("2026-12-17T21:00:00Z")));
    assertFalse(PlageSilencieusePush.estActive(Instant.parse("2026-12-17T20:59:59Z")));
  }

  @Test
  void estActive_enPleineJournee_retourneFalse() {
    assertFalse(PlageSilencieusePush.estActive(Instant.parse("2026-10-17T10:00:00Z")));
  }
}
