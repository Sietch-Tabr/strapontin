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

/// Tests unitaires pour {@link BilanFetch}.
///
/// @since 2.7.0
/// @author Kwaite
class BilanFetchTest {

  private static final int SEUIL = BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT;

  @Test
  void panneTotale_touslesAppelsEnEchecAuDessusDuSeuil_retourneVrai() {
    BilanFetch bilan = new BilanFetch(SEUIL, SEUIL);
    assertTrue(bilan.panneTotale(SEUIL));
  }

  @Test
  void panneTotale_touslesAppelsEnEchecSousLeSeuil_retourneFaux() {
    BilanFetch bilan = new BilanFetch(SEUIL - 1, SEUIL - 1);
    assertFalse(bilan.panneTotale(SEUIL));
  }

  @Test
  void panneTotale_echecPartiel_retourneFaux() {
    BilanFetch bilan = new BilanFetch(SEUIL, SEUIL - 1);
    assertFalse(bilan.panneTotale(SEUIL));
  }

  @Test
  void evaluer_aucunAppel_retourneVide() {
    BilanFetch bilan = new BilanFetch(0, 0);
    assertTrue(bilan.evaluer("fetch:region", "Digitick", SEUIL).isEmpty());
  }

  @Test
  void evaluer_tousReussis_retourneVide() {
    BilanFetch bilan = new BilanFetch(5, 0);
    assertTrue(bilan.evaluer("fetch:region", "Digitick", SEUIL).isEmpty());
  }

  @Test
  void evaluer_panneTotale_retourneNiveauErrorEtMessageComplet() {
    BilanFetch bilan = new BilanFetch(SEUIL, SEUIL);
    Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Digitick", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("error", entree.get().niveau());
    assertTrue(entree.get().message().contains("Digitick"));
    assertTrue(entree.get().message().contains("intégralement en échec"));
    assertTrue(entree.get().message().contains(SEUIL + "/" + SEUIL));
    assertEquals(SEUIL, entree.get().meta().get("appels"));
    assertEquals(SEUIL, entree.get().meta().get("erreursHttp"));
  }

  @Test
  void evaluer_panneTotaleSousLeSeuil_retourneNiveauWarn() {
    BilanFetch bilan = new BilanFetch(SEUIL - 1, SEUIL - 1);
    Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Digitick", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("warn", entree.get().niveau());
  }

  @Test
  void evaluer_echecPartiel_retourneNiveauWarnEtMessagePartiel() {
    BilanFetch bilan = new BilanFetch(5, 2);
    Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Eventim", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("warn", entree.get().niveau());
    assertTrue(entree.get().message().contains("en échec partiel"));
    assertTrue(entree.get().message().contains("2/5"));
  }
}
