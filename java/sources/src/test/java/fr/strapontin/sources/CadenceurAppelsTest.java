/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link CadenceurAppels}.
class CadenceurAppelsTest {

  @Test
  void attendre_deuxAppelsRapproches_respecteLeDelaiMinimal() {
    CadenceurAppels cadenceur = new CadenceurAppels(100);
    long debut = System.currentTimeMillis();
    cadenceur.attendre();
    cadenceur.attendre();
    long ecoule = System.currentTimeMillis() - debut;
    assertTrue(ecoule >= 100, "écart observé=" + ecoule + "ms, attendu >= 100ms");
  }

  @Test
  void attendre_appelsEspacesNaturellement_neBloquePas() throws InterruptedException {
    CadenceurAppels cadenceur = new CadenceurAppels(50);
    cadenceur.attendre();
    Thread.sleep(60);
    long debut = System.currentTimeMillis();
    cadenceur.attendre();
    long ecoule = System.currentTimeMillis() - debut;
    assertTrue(ecoule < 50, "aucune attente supplémentaire attendue, observé=" + ecoule + "ms");
  }
}
