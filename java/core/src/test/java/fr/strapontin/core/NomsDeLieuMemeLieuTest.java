/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Noms d'un même lieu publiés par deux billetteries (paires réelles du
 * 06/10/2026) et lieux voisins distincts : critère fort de fusion automatique.
 */
class NomsDeLieuMemeLieuTest {

  private static String n(final String nom) {
    return Normalisation.normaliserTexte(nom);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "L'Archipel|Larchipel,theatre,cinema,musique Paris 10",
        "L'Européen|Leuropeen Paris 17",
        "Église Saint-Louis-en-l'Île|Eglise Saint Louis en Ile PARIS 04"
      })
  void designentFortementLeMemeLieu_memeLieuSousDeuxNoms_vrai(final String a, final String b) {
    assertTrue(NomsDeLieu.designentFortementLeMemeLieu(n(a), n(b), "paris"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "La Cigale|La Boule Noire",
        "Théâtre Montmartre Galabru|Élysée Montmartre",
        "Théâtre de Poche Montparnasse|Théâtre Montparnasse"
      })
  void designentFortementLeMemeLieu_lieuxVoisinsDistincts_faux(final String a, final String b) {
    assertFalse(NomsDeLieu.designentFortementLeMemeLieu(n(a), n(b), "paris"));
  }

  // Relevés en prod le 16/10/2026 : la même salle sous le nom de chaque billetterie.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Théâtre des Cordeliers|Les Cordeliers Romans sur Isere|Romans sur Isère",
        "Le Mas D'Hiver|Le Mas Dhiver Puget sur Argens|Puget sur Argens",
        "L'Empreinte|Lempreinte Savigny le Temple Cedex|Savigny le Temple"
      })
  void designentFortementLeMemeLieu_memeSalleSousLeNomDeChaqueBilletterie_vrai(
      final String a, final String b, final String ville) {
    assertTrue(NomsDeLieu.designentFortementLeMemeLieu(n(a), n(b), n(ville)));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Théâtre du Chariot|Théâtre du Châtelet|Paris",
        "Théâtre de Poche Montparnasse|Théâtre Montparnasse|Paris",
        "Le Millenaire Savigny le Temple|Lempreinte Savigny le Temple Cedex|Savigny le Temple",
        "Théâtre de Paris|Théâtre de Paris Nord|Paris"
      })
  void designentFortementLeMemeLieu_lieuxDistinctsMemeVille_faux(
      final String a, final String b, final String ville) {
    assertFalse(NomsDeLieu.designentFortementLeMemeLieu(n(a), n(b), n(ville)));
  }
}
