/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link UtilisateurDetails}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class UtilisateurDetailsTest {

  @Test
  void isAccountNonLocked_compteNonVerrouille_retourneTrue() {
    final UtilisateurDetails details =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);

    assertTrue(details.isAccountNonLocked());
  }

  @Test
  void isAccountNonLocked_compteVerrouille_retourneFalse() {
    final UtilisateurDetails details =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, true);

    assertFalse(details.isAccountNonLocked());
  }
}
