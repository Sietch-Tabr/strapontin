/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

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
}
