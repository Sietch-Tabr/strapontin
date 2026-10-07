/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CauseNotificationsNonEnvoyeesTest {

  @Test
  void decrire_emailActif_envoiEnEchec() {
    final Map<String, Object> details =
        CauseNotificationsNonEnvoyees.decrire(true, false, false, null, null);

    assertTrue(((String) details.get("cause")).startsWith("Envoi en échec"));
  }

  @Test
  void decrire_pushAvecAppareil_envoiEnEchec() {
    assertTrue(
        ((String) CauseNotificationsNonEnvoyees.decrire(false, true, true, null, null).get("cause"))
            .startsWith("Envoi en échec"));
  }

  @Test
  void decrire_pushSansAppareilApresRetrait_donneLaDateEtLAvertissement() {
    final Map<String, Object> details =
        CauseNotificationsNonEnvoyees.decrire(
            false, true, false, LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27));

    assertEquals(
        "Aucun canal joignable : email désactivé, aucun appareil abonné au push (dernier appareil"
            + " retiré automatiquement le 26/09/2026).",
        details.get("cause"));
    assertTrue(((String) details.get("action")).contains("prévenu par email le 27/09/2026"));
  }

  @Test
  void decrire_pushJamaisAbonne_sansDate() {
    assertEquals(
        "Aucun canal joignable : email désactivé, aucun appareil abonné au push.",
        CauseNotificationsNonEnvoyees.decrire(false, true, false, null, null).get("cause"));
  }
}
