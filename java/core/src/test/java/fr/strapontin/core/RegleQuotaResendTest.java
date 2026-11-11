/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegleQuotaResendTest {

  @Test
  void courant_compteurAbsent_estVide() {
    final CompteurEnvoisResend compteur = RegleQuotaResend.courant(Optional.empty());

    assertEquals(0, compteur.envoyesJour());
    assertEquals(0, compteur.envoyesMois());
    assertFalse(compteur.alerteJourEnvoyee());
  }

  @Test
  void courant_jourEcoule_remetLeJourEtRearmeLAlerteDuJour() {
    final String jour = RegleQuotaResend.jourCourant();
    final CompteurEnvoisResend hier =
        new CompteurEnvoisResend(
            "2000-01-01", 90, RegleQuotaResend.moisCourant(jour), 90, true, true);

    final CompteurEnvoisResend compteur = RegleQuotaResend.courant(Optional.of(hier));

    assertEquals(jour, compteur.jour());
    assertEquals(0, compteur.envoyesJour());
    assertEquals(90, compteur.envoyesMois());
    assertFalse(compteur.alerteJourEnvoyee());
    assertTrue(compteur.alerteMoisEnvoyee());
  }

  @Test
  void courant_moisEcoule_remetLeJourEtLeMois() {
    final CompteurEnvoisResend ancien =
        new CompteurEnvoisResend("2000-01-01", 90, "2000-01", 2900, true, true);

    final CompteurEnvoisResend compteur = RegleQuotaResend.courant(Optional.of(ancien));

    assertEquals(0, compteur.envoyesJour());
    assertEquals(0, compteur.envoyesMois());
    assertFalse(compteur.alerteMoisEnvoyee());
  }

  @Test
  void estAtteint_limiteDuJourOuDuMois_retourneVrai() {
    final String jour = RegleQuotaResend.jourCourant();
    final String mois = RegleQuotaResend.moisCourant(jour);

    assertTrue(
        RegleQuotaResend.estAtteint(
            new CompteurEnvoisResend(jour, RegleQuotaResend.LIMITE_JOUR, mois, 0, false, false)));
    assertTrue(
        RegleQuotaResend.estAtteint(
            new CompteurEnvoisResend(jour, 0, mois, RegleQuotaResend.LIMITE_MOIS, false, false)));
    assertFalse(
        RegleQuotaResend.estAtteint(new CompteurEnvoisResend(jour, 99, mois, 2999, false, false)));
  }

  @Test
  void apresEnvoi_incrementeJourEtMoisSansToucherAuxAlertes() {
    final CompteurEnvoisResend apres =
        RegleQuotaResend.apresEnvoi(
            new CompteurEnvoisResend("2026-11-10", 5, "2026-11", 50, true, false));

    assertEquals(6, apres.envoyesJour());
    assertEquals(51, apres.envoyesMois());
    assertTrue(apres.alerteJourEnvoyee());
    assertFalse(apres.alerteMoisEnvoyee());
  }
}
