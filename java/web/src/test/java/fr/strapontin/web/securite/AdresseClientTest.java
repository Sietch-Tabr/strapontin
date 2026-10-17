/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class AdresseClientTest {

  @Test
  void cle_ipv4_estConservee() {
    assertEquals("203.0.113.7", AdresseClient.cle("203.0.113.7"));
  }

  @Test
  void cle_ipv6MemePrefixe64_donneLaMemeCle() {
    assertEquals(
        AdresseClient.cle("2001:db8:1:2:aaaa:bbbb:cccc:dddd"),
        AdresseClient.cle("2001:db8:1:2:1111:2222:3333:4444"));
  }

  @Test
  void cle_ipv6PrefixesDifferents_donneDesClesDifferentes() {
    assertNotEquals(AdresseClient.cle("2001:db8:1:2::1"), AdresseClient.cle("2001:db8:1:3::1"));
  }

  @Test
  void cle_ipv6_estLePrefixeSansZerosSuperflus() {
    assertEquals("2001:db8:1:2::/64", AdresseClient.cle("2001:0db8:0001:0002:0:0:0:9"));
  }

  @Test
  void cle_ipv4MappeeEnIpv6_retombeSurLIpv4() {
    assertEquals("203.0.113.7", AdresseClient.cle("::ffff:203.0.113.7"));
  }

  @Test
  void cle_absenteOuVide_donneInconnu() {
    assertEquals("inconnu", AdresseClient.cle(null));
    assertEquals("inconnu", AdresseClient.cle("  "));
  }
}
