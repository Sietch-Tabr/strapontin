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

/**
 * Tests unitaires pour {@link BilanFetch}.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class BilanFetchTest {

  private static final int SEUIL = BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT;

  @Test
  void panneTotale_touslesAppelsEnEchecAuDessusDuSeuil_retourneVrai() {
    final BilanFetch bilan = new BilanFetch(SEUIL, SEUIL);
    assertTrue(bilan.panneTotale(SEUIL));
  }

  @Test
  void panneTotale_touslesAppelsEnEchecSousLeSeuil_retourneFaux() {
    final BilanFetch bilan = new BilanFetch(SEUIL - 1, SEUIL - 1);
    assertFalse(bilan.panneTotale(SEUIL));
  }

  @Test
  void panneTotale_echecPartiel_retourneFaux() {
    final BilanFetch bilan = new BilanFetch(SEUIL, SEUIL - 1);
    assertFalse(bilan.panneTotale(SEUIL));
  }

  @Test
  void evaluer_aucunAppel_retourneVide() {
    final BilanFetch bilan = new BilanFetch(0, 0);
    assertTrue(bilan.evaluer("fetch:region", "Eventim", SEUIL).isEmpty());
  }

  @Test
  void evaluer_tousReussis_retourneVide() {
    final BilanFetch bilan = new BilanFetch(5, 0);
    assertTrue(bilan.evaluer("fetch:region", "Eventim", SEUIL).isEmpty());
  }

  @Test
  void evaluer_panneTotale_retourneNiveauErrorEtMessageComplet() {
    final BilanFetch bilan = new BilanFetch(SEUIL, SEUIL);
    final Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Eventim", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("error", entree.get().niveau());
    assertTrue(entree.get().message().contains("Eventim"));
    assertTrue(entree.get().message().contains("intégralement en échec"));
    assertTrue(entree.get().message().contains(SEUIL + "/" + SEUIL));
    assertEquals(SEUIL, entree.get().meta().get("appels"));
    assertEquals(SEUIL, entree.get().meta().get("erreursHttp"));
  }

  @Test
  void evaluer_panneTotaleSousLeSeuil_retourneNiveauWarn() {
    final BilanFetch bilan = new BilanFetch(SEUIL - 1, SEUIL - 1);
    final Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Eventim", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("warn", entree.get().niveau());
  }

  @Test
  void evaluer_echecPartiel_retourneNiveauWarnEtMessagePartiel() {
    final BilanFetch bilan = new BilanFetch(5, 2);
    final Optional<BilanFetch.EntreeLog> entree = bilan.evaluer("fetch:region", "Eventim", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("warn", entree.get().niveau());
    assertTrue(entree.get().message().contains("en échec partiel"));
    assertTrue(entree.get().message().contains("2/5"));
  }

  @Test
  void repondVide_toutesReponsesVidesAuDessusDuSeuil_retourneVrai() {
    final int appels = BilanFetch.SEUIL_APPELS_MIN_REPONSES_VIDES;
    assertTrue(new BilanFetch(appels, 0, appels).repondVide());
  }

  @Test
  void repondVide_sousLeSeuilOuAvecUnResultat_retourneFaux() {
    final int appels = BilanFetch.SEUIL_APPELS_MIN_REPONSES_VIDES;
    assertFalse(new BilanFetch(appels - 1, 0, appels - 1).repondVide());
    assertFalse(new BilanFetch(appels, 0, appels - 1).repondVide());
    assertFalse(new BilanFetch(appels, 1, appels).repondVide());
  }

  @Test
  void evaluer_sourceRepondantToujoursVide_retourneWarnSansParlerDePanne() {
    final int appels = BilanFetch.SEUIL_APPELS_MIN_REPONSES_VIDES;
    final Optional<BilanFetch.EntreeLog> entree =
        new BilanFetch(appels, 0, appels).evaluer("reconciliation:nocturne", "Wikidata", SEUIL);

    assertTrue(entree.isPresent());
    assertEquals("warn", entree.get().niveau());
    assertTrue(entree.get().message().contains("répond sans aucun résultat"));
  }

  @Test
  void evaluer_reponsesVidesPartielles_retourneVide() {
    assertTrue(
        new BilanFetch(60, 0, 30).evaluer("reconciliation:nocturne", "Wikidata", SEUIL).isEmpty());
  }

  @Test
  void evaluer_causesAttribuees_lesNommeDansLeMessageEtLesMeta() {
    final BilanFetch bilan =
        new BilanFetch(
            252, 12, 0, java.util.Map.of("connexion refusée", 9, "échange TLS interrompu", 3));

    final var entree = bilan.evaluer("fetch:artistes:batch", "Eventim", SEUIL).orElseThrow();

    org.junit.jupiter.api.Assertions.assertTrue(
        entree.message().endsWith(" : connexion refusée × 9, échange TLS interrompu × 3"));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Map.of("connexion refusée", 9, "échange TLS interrompu", 3),
        entree.meta().get("causes"));
  }

  @Test
  void evaluer_sansCauseAttribuee_messageInchange() {
    final var entree =
        new BilanFetch(SEUIL, 1).evaluer("fetch:region", "Eventim", SEUIL).orElseThrow();

    org.junit.jupiter.api.Assertions.assertFalse(entree.message().contains(" : "));
  }
}
