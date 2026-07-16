/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link CompteurBilanFetch} (#909).
///
/// @since 2.7.0
/// @author Kwaite
class CompteurBilanFetchTest {

  @Test
  void preleverBilan_aucunEnregistrement_retourneZeroPartout() {
    CompteurBilanFetch compteur = new CompteurBilanFetch();
    BilanFetch bilan = compteur.preleverBilan();
    assertEquals(0, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_appelsEtErreursCumules_retourneLesTotaux() {
    CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerErreur();

    BilanFetch bilan = compteur.preleverBilan();
    assertEquals(3, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerErreur();
    compteur.preleverBilan();

    BilanFetch second = compteur.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }
}
