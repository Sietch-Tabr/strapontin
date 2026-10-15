/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link CadenceurAppelsMemoire}.
 */
class CadenceurAppelsMemoireTest {

  @Test
  void attendre_deuxAppelsRapproches_respecteLeDelaiMinimal() {
    final CadenceurAppels cadenceur = new CadenceurAppelsMemoire(100);
    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;
    assertTrue(ecoule >= 100, "écart observé=" + ecoule + "ms, attendu >= 100ms");
  }

  @Test
  void attendre_appelsEspacesNaturellement_neBloquePas() throws InterruptedException {
    final CadenceurAppels cadenceur = new CadenceurAppelsMemoire(50);
    cadenceur.attendre();
    Thread.sleep(60);
    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;
    assertTrue(ecoule < 50, "aucune attente supplémentaire attendue, observé=" + ecoule + "ms");
  }

  @Test
  void ralentir_refusRepetes_doubleLEcartJusquAuPlafond() {
    final CadenceurAppelsMemoire cadenceur = new CadenceurAppelsMemoire(10);
    cadenceur.ralentir();
    assertEquals(20, cadenceur.delaiCourantMs());
    for (int i = 0; i < 10; i++) {
      cadenceur.ralentir();
    }
    assertEquals(80, cadenceur.delaiCourantMs());
  }

  @Test
  void apaiser_vingtAppelsAcceptesDAffilee_raccourcitLEcartD_unCran() {
    final CadenceurAppelsMemoire cadenceur = new CadenceurAppelsMemoire(10);
    cadenceur.ralentir();
    cadenceur.ralentir();
    for (int i = 0; i < 19; i++) {
      cadenceur.apaiser();
    }
    assertEquals(40, cadenceur.delaiCourantMs());
    cadenceur.apaiser();
    assertEquals(20, cadenceur.delaiCourantMs());
  }

  @Test
  void apaiser_sansRefusPrealable_neDescendPasSousLEcartDeBase() {
    final CadenceurAppelsMemoire cadenceur = new CadenceurAppelsMemoire(10);
    for (int i = 0; i < 100; i++) {
      cadenceur.apaiser();
    }
    assertEquals(10, cadenceur.delaiCourantMs());
  }

  @Test
  void ralentir_apresUnRefus_lAttenteSuivanteEstPlusLongue() {
    final CadenceurAppelsMemoire cadenceur = new CadenceurAppelsMemoire(50);
    cadenceur.attendre();
    cadenceur.ralentir();
    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;
    assertTrue(ecoule >= 95, "écart observé=" + ecoule + "ms, attendu >= 100ms");
  }
}
