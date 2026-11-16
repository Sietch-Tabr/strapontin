/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IdentiteHttpTest {

  private static final String URL = "https://exemple.test/projet";

  @Test
  void userAgent_avecContact_porteVersionAdresseEtContact() {
    final IdentiteHttp identite = new IdentiteHttp("3.1.5", URL, () -> " moi@exemple.test ");

    assertEquals(
        "Strapontin/3.1.5 (https://exemple.test/projet; moi@exemple.test)", identite.userAgent());
  }

  @Test
  void userAgent_contactVideOuNul_neGardeQueLAdresseDuProjet() {
    assertEquals(
        "Strapontin/3.1.5 (https://exemple.test/projet)",
        new IdentiteHttp("3.1.5", URL, () -> "").userAgent());
    assertEquals(
        "Strapontin/3.1.5 (https://exemple.test/projet)",
        new IdentiteHttp("3.1.5", URL, () -> null).userAgent());
  }

  @Test
  void userAgent_contactModifie_estRelu() {
    final AtomicReference<String> contact = new AtomicReference<>("a@exemple.test");
    final IdentiteHttp identite = new IdentiteHttp("3.1.5", URL, contact::get);

    contact.set("b@exemple.test");

    assertEquals(
        "Strapontin/3.1.5 (https://exemple.test/projet; b@exemple.test)", identite.userAgent());
  }

  @Test
  void urlProjet_retourneLAdresseDuProjet() {
    assertEquals(URL, new IdentiteHttp("3.1.5", URL, () -> "").urlProjet());
  }
}
