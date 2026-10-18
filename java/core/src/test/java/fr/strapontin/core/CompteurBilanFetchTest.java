/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link CompteurBilanFetch}.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class CompteurBilanFetchTest {

  @Test
  void preleverBilan_aucunEnregistrement_retourneZeroPartout() {
    final CompteurBilanFetch compteur = new CompteurBilanFetch();
    final BilanFetch bilan = compteur.preleverBilan();
    assertEquals(0, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_appelsEtErreursCumules_retourneLesTotaux() {
    final CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerErreur();

    final BilanFetch bilan = compteur.preleverBilan();
    assertEquals(3, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    final CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerErreur();
    compteur.preleverBilan();

    final BilanFetch second = compteur.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }

  @Test
  void preleverBilan_reponsesVides_cumuleEtRemetAZero() {
    final CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerReponseVide();

    assertEquals(1, compteur.preleverBilan().reponsesVides());
    assertEquals(0, compteur.preleverBilan().reponsesVides());
  }

  @Test
  void preleverBilan_erreursAvecCause_regroupeParCauseEtRemetAZero() {
    final CompteurBilanFetch compteur = new CompteurBilanFetch();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerAppel();
    compteur.enregistrerErreur("connexion refusée");
    compteur.enregistrerErreur("connexion refusée");
    compteur.enregistrerErreur("délai dépassé");

    final BilanFetch bilan = compteur.preleverBilan();

    org.junit.jupiter.api.Assertions.assertEquals(3, bilan.erreursHttp());
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Map.of("connexion refusée", 2, "délai dépassé", 1), bilan.causes());
    org.junit.jupiter.api.Assertions.assertTrue(compteur.preleverBilan().causes().isEmpty());
  }
}
