/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlateauSoireeTest {

  @Test
  void sansArtistePrincipal_retireLePrincipalEtLesDoublons() {
    assertEquals(
        List.of("Alain Chamfort"),
        PlateauSoiree.sansArtistePrincipal(
            List.of("Charlie Winston", "Alain Chamfort", "ALAIN CHAMFORT"), "Charlie Winston"));
  }
}
