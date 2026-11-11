/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link FiltreConcertsBuilder}.
 *
 * @since 2.6.4
 * @author Kwaite
 */
class FiltreConcertsBuilderTest {

  @Test
  void appliquerMois_moisValide_ajouteLaClauseEtLesParametres() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerMois(where, params, "2026-07");

    assertTrue(where.toString().contains("moisDebut"));
    assertEquals(LocalDate.of(2026, 7, 1), params.get("moisDebut"));
    assertEquals(LocalDate.of(2026, 8, 1), params.get("moisFin"));
  }

  @Test
  void appliquerMois_formatNonConforme_sansEffet() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerMois(where, params, "pas-une-date");

    assertEquals(0, where.length());
    assertTrue(params.isEmpty());
  }

  // "2026-13"/"2026-00" passent la regex de forme (4 chiffres-tiret-2 chiffres) mais ne
  // sont pas des mois calendaires valides — LocalDate.parse levait une exception non interceptée
  // avant correctif, provoquant un 500 au lieu du contrat documenté "sans effet si invalide".
  @Test
  void appliquerMois_moisCalendaireInvalide13_sansEffetNiException() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    assertDoesNotThrow(() -> FiltreConcertsBuilder.appliquerMois(where, params, "2026-13"));

    assertEquals(0, where.length());
    assertTrue(params.isEmpty());
  }

  @Test
  void appliquerMois_moisCalendaireInvalide00_sansEffetNiException() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    assertDoesNotThrow(() -> FiltreConcertsBuilder.appliquerMois(where, params, "2026-00"));

    assertEquals(0, where.length());
    assertTrue(params.isEmpty());
  }

  @Test
  void appliquerMois_moisVide_sansEffet() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerMois(where, params, "");

    assertFalse(where.toString().contains("moisDebut"));
  }

  @Test
  void clauseTri_nouveaute_trieParFirstSeenDesc() {
    assertEquals(
        "c.first_seen DESC, c.date ASC, c.id ASC", FiltreConcertsBuilder.clauseTri("nouveaute"));
  }

  @Test
  void clauseTri_date_trieParDateAsc() {
    assertEquals(
        "c.date ASC, c.artiste ASC, c.heure ASC NULLS LAST, c.id ASC",
        FiltreConcertsBuilder.clauseTri("date"));
  }

  @Test
  void clauseTri_valeurInconnue_replieSurDate() {
    assertEquals(
        "c.date ASC, c.artiste ASC, c.heure ASC NULLS LAST, c.id ASC",
        FiltreConcertsBuilder.clauseTri("autre-chose"));
  }

  @Test
  void tronquer_chaineVide_retourneChaineVide() {
    assertEquals("", FiltreConcertsBuilder.tronquer("", 10));
  }

  @Test
  void tronquer_pluscourteQueLaLimite_estInchangee() {
    assertEquals("radiohead", FiltreConcertsBuilder.tronquer("radiohead", 100));
  }

  @Test
  void tronquer_longueurExactementLaLimite_estInchangee() {
    assertEquals("12345", FiltreConcertsBuilder.tronquer("12345", 5));
  }

  @Test
  void tronquer_pluslongueQueLaLimite_estTronqueeALaBorne() {
    assertEquals("1234567890", FiltreConcertsBuilder.tronquer("1234567890abcdef", 10));
  }

  @Test
  void tronquer_espacesDeBordure_sontRetiresAvantTroncature() {
    assertEquals("radiohead", FiltreConcertsBuilder.tronquer("  radiohead  ", 100));
  }

  @Test
  void appliquerGenre_genreVide_sansEffet() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerGenre(where, params, "");

    assertEquals(0, where.length());
    assertTrue(params.isEmpty());
  }

  @Test
  void appliquerGenre_genreNonVide_ajouteLaClauseIlike() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerGenre(where, params, "Pop");

    assertTrue(where.toString().contains("c.genre ILIKE :genre"));
    assertEquals("Pop", params.get("genre"));
  }

  @Test
  void appliquerClassification_classificationVide_sansEffet() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerClassification(where, params, "");

    assertEquals(0, where.length());
    assertTrue(params.isEmpty());
  }

  @Test
  void appliquerClassification_classificationNonVide_ajouteLaClauseEgalite() {
    final StringBuilder where = new StringBuilder();
    final Map<String, Object> params = new HashMap<>();

    FiltreConcertsBuilder.appliquerClassification(where, params, "concert");

    assertTrue(where.toString().contains("c.classification = :classification"));
    assertEquals("concert", params.get("classification"));
  }
}
