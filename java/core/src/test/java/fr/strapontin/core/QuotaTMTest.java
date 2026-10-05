/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Quota Ticketmaster en vigueur selon la remise à zéro ({@link QuotaTM#enVigueur}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class QuotaTMTest {

  private static final Instant MAINTENANT = Instant.parse("2026-10-04T00:05:00Z");

  @Test
  void enVigueur_remiseAZeroAVenir_renvoieLeQuotaTelQuel() {
    final QuotaTM quota =
        new QuotaTM(0, 5000, MAINTENANT.plusSeconds(3600).toEpochMilli(), "2026-10-03T22:06:00Z");

    assertSame(quota, quota.enVigueur(MAINTENANT));
  }

  @Test
  void enVigueur_remiseAZeroPassee_renvoieLeQuotaComplet() {
    final long reset = MAINTENANT.minusSeconds(3600).toEpochMilli();

    final QuotaTM renouvele =
        new QuotaTM(0, 5000, reset, "2026-10-03T22:06:00Z").enVigueur(MAINTENANT);

    assertEquals(new QuotaTM(5000, 5000, 0L, Instant.ofEpochMilli(reset).toString()), renouvele);
  }

  // Dev, 03/10/2026 : premier quota connu = un refus (total 0). Renouvelé à 0, le cron restait
  // suspendu sans date de nouvel essai, et aucun appel ne pouvait plus révéler le vrai quota.
  @Test
  void enVigueur_remiseAZeroPasseeSansTotalConnu_renvoieNull() {
    final QuotaTM quota =
        new QuotaTM(0, 0, MAINTENANT.minusSeconds(3600).toEpochMilli(), "2026-10-03T22:06:00Z");

    assertNull(quota.enVigueur(MAINTENANT));
  }

  @Test
  void enVigueur_sansRemiseAZero_renvoieLeQuotaTelQuel() {
    final QuotaTM quota = new QuotaTM(4200, 5000, 0L, "2026-10-03T22:06:00Z");

    assertSame(quota, quota.enVigueur(MAINTENANT));
  }
}
