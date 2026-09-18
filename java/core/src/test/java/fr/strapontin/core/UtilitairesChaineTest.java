/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link UtilitairesChaine} — échappement JSON manuel (notifications push) et
 * critère de containment ratio partagé par {@link CorrespondanceSalle}/{@link
 * CorrespondanceArtiste}.
 */
class UtilitairesChaineTest {

  // --- echapperJson ---

  @Test
  void echapperJson_chaineSansCaractereSpecial_retourneInchangee() {
    assertEquals("Radiohead", UtilitairesChaine.echapperJson("Radiohead"));
  }

  @Test
  void echapperJson_guillemets_echappes() {
    assertEquals("Le \\\"vrai\\\" concert", UtilitairesChaine.echapperJson("Le \"vrai\" concert"));
  }

  @Test
  void echapperJson_antislash_echappe() {
    assertEquals("C:\\\\chemin", UtilitairesChaine.echapperJson("C:\\chemin"));
  }

  @Test
  void echapperJson_sautsDeLigne_echappes() {
    assertEquals("ligne1\\nligne2\\rfin", UtilitairesChaine.echapperJson("ligne1\nligne2\rfin"));
  }

  @Test
  void echapperJson_antislashAvantGuillemet_ordreCorrect() {
    // L'antislash doit être échappé avant le guillemet, sinon le \" produit par l'échappement
    // du guillemet serait lui-même ré-échappé.
    assertEquals("a\\\\\\\"b", UtilitairesChaine.echapperJson("a\\\"b"));
  }

  @Test
  void echapperJson_chaineVide_retourneVide() {
    assertEquals("", UtilitairesChaine.echapperJson(""));
  }

  // --- estContainmentRatio ---

  @Test
  void estContainmentRatio_courtContenuAvecRatioSuffisant_retourneTrue() {
    assertTrue(UtilitairesChaine.estContainmentRatio("aliz", "alizee", 4, 0.65));
  }

  @Test
  void estContainmentRatio_ratioInsuffisant_retourneFalse() {
    assertFalse(UtilitairesChaine.estContainmentRatio("abcd", "abcdefghij", 4, 0.65));
  }

  @Test
  void estContainmentRatio_courtSousLongueurMinimale_retourneFalse() {
    assertFalse(UtilitairesChaine.estContainmentRatio("jo", "john", 4, 0.65));
  }

  @Test
  void estContainmentRatio_courtNonContenuDansLong_retourneFalse() {
    assertFalse(UtilitairesChaine.estContainmentRatio("aliz", "wxyzee", 4, 0.65));
  }

  @Test
  void estContainmentRatio_ordreDesParametresIndifferent_retourneMemeResultat() {
    assertTrue(UtilitairesChaine.estContainmentRatio("alizee", "aliz", 4, 0.65));
  }

  // --- estEgaliteSansEspaces ---

  @Test
  void estEgaliteSansEspaces_espaceSupplementaire_retourneTrue() {
    assertTrue(UtilitairesChaine.estEgaliteSansEspaces("youssou n dour", "youssou ndour"));
  }

  @Test
  void estEgaliteSansEspaces_chainesIdentiques_retourneTrue() {
    assertTrue(UtilitairesChaine.estEgaliteSansEspaces("radiohead", "radiohead"));
  }

  @Test
  void estEgaliteSansEspaces_differenceAutreQueEspace_retourneFalse() {
    assertFalse(UtilitairesChaine.estEgaliteSansEspaces("youssou n dour", "youssou ndourr"));
  }
}
