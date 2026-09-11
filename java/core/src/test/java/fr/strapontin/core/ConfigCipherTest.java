/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link ConfigCipher} (extrait de web.ConfigService).
 *
 * <p>Valide la cohérence chiffrement/déchiffrement AES-256-GCM et la compatibilité avec le format
 * produit par l'ancien module TypeScript.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class ConfigCipherTest {

  @Test
  void chiffrerDechiffrer_valeurRonde_retourneValeurOriginale() {
    final ConfigCipher cipher = new ConfigCipher("mon-secret-de-test-32-chars-ok!!");
    final String valeur = "clé-api-test-12345";
    final String chiffre = cipher.chiffrer(valeur);
    assertEquals(valeur, cipher.dechiffrer(chiffre));
  }

  @Test
  void chiffrer_produitResultatDifferentAChaqueFois() {
    final ConfigCipher cipher = new ConfigCipher("mon-secret-de-test-32-chars-ok!!");
    final String valeur = "meme-valeur";
    final String chiffre1 = cipher.chiffrer(valeur);
    final String chiffre2 = cipher.chiffrer(valeur);
    // IV aléatoire — deux chiffrements ne doivent jamais produire le même résultat
    assertNotEquals(chiffre1, chiffre2);
  }

  @Test
  void dechiffrer_avecMauvaisSecret_lancerException() {
    final ConfigCipher cipher1 = new ConfigCipher("secret-1-de-test-32-chars-ok!!");
    final ConfigCipher cipher2 = new ConfigCipher("secret-2-de-test-32-chars-ok!!");
    final String valeur = "valeur-secrete";
    final String chiffre = cipher1.chiffrer(valeur);
    // Déchiffrement avec un mauvais secret doit échouer
    assertThrows(Exception.class, () -> cipher2.dechiffrer(chiffre));
  }

  @Test
  void chiffrerDechiffrer_valeurAvecCaracteresSpeciaux_retourneValeurOriginale() {
    final ConfigCipher cipher = new ConfigCipher("mon-secret-de-test-32-chars-ok!!");
    final String valeur = "cle-api=\"éàü\"\n{\"json\":true}\t fin";
    assertEquals(valeur, cipher.dechiffrer(cipher.chiffrer(valeur)));
  }

  @Test
  void chiffrerDechiffrer_valeurVide_retourneValeurOriginale() {
    final ConfigCipher cipher = new ConfigCipher("mon-secret-de-test-32-chars-ok!!");
    final String valeur = "";
    assertEquals(valeur, cipher.dechiffrer(cipher.chiffrer(valeur)));
  }

  @Test
  void deuxCiphersMemeSecret_produisentUneCleCompatible() {
    // Web et worker dérivent chacun leur propre ConfigCipher depuis le même APP_CONFIG_SECRET —
    // les deux doivent pouvoir déchiffrer une valeur chiffrée par l'autre.
    final ConfigCipher cipherWeb = new ConfigCipher("secret-partage-32-caracteres-ok");
    final ConfigCipher cipherWorker = new ConfigCipher("secret-partage-32-caracteres-ok");
    final String valeur = "valeur-partagee";
    assertEquals(valeur, cipherWorker.dechiffrer(cipherWeb.chiffrer(valeur)));
  }
}
