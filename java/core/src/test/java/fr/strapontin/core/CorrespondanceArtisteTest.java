/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link CorrespondanceArtiste#estDoublonArtiste} — dédoublonnage d'artistes.
 *
 * <p>Noms passés déjà normalisés (via {@link Normalisation#normaliserTexte}), comme les
 * consommateurs réels de cette classe ({@code ReconciliateurArtistes}, {@code ArtisteService}).
 */
class CorrespondanceArtisteTest {

  @Test
  void estDoublonArtiste_chainesIdentiques_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("radiohead", "radiohead"));
  }

  @Test
  void estDoublonArtiste_hommageEtArtisteImite_retourneFalse() {
    assertFalse(
        CorrespondanceArtiste.estDoublonArtiste(
            "ac dc live tribute by poun black bomb a smack", "ac dc"));
    assertFalse(CorrespondanceArtiste.estMemeAffiche("ac dc", "ac dc tribute"));
  }

  @Test
  void estMemeAffiche_deuxHommagesAuMemeArtiste_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estMemeAffiche("ac dc tribute", "ac dc tribute 1ere partie"));
  }

  @Test
  void estDoublonArtiste_sansEspaceJaroSuffisant_retourneTrue() {
    // Jaro("krash karma", "krashkarma") = 0.97 >= seuil 0.95
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("krash karma", "krashkarma"));
  }

  @Test
  void estDoublonArtiste_prefixeCommunJaroInsuffisant_retourneFalse() {
    // Jaro("julien dore", "julien morel") = 0.914 < seuil 0.95, aucun containment applicable
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("julien dore", "julien morel"));
  }

  @Test
  void estDoublonArtiste_troncatureTMCourte_retourneTrue() {
    // "aliz" (4) / "alizee" (6) — ratio 0.667 >= 0.65, containment vérifié
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("aliz", "alizee"));
  }

  @Test
  void estDoublonArtiste_troncatureTMUnCaractere_retourneTrue() {
    // "fawn" (4) / "fawnn" (5) — ratio 0.8 >= 0.65, containment vérifié
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("fawn", "fawnn"));
  }

  @Test
  void estDoublonArtiste_variationLongueSuffixeNumerique_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("star academy", "star academy 1"));
  }

  @Test
  void estDoublonArtiste_apostropheOmiseSurNomCourt_retourneTrue() {
    // Jaro("n dour", "ndour") = 0.944 < seuil 0.95 (nom court, l'espace insérée pèse davantage) ;
    // égalité sans espaces couvre le cas.
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("n dour", "ndour"));
  }

  @Test
  void estDoublonArtiste_containmentSousLongueurMinimale_retourneFalse() {
    // "jo" (2) < LONGUEUR_MIN_CONTAINMENT_ARTISTE (4), même si contenu dans "john"
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("jo", "john"));
  }

  @Test
  void estDoublonArtiste_containmentSousRatioMinimal_retourneFalse() {
    // "abcd" (4) / "abcdefghij" (10) — ratio 0.4 < 0.65, malgré le containment
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("abcd", "abcdefghij"));
  }

  @Test
  void estDoublonArtiste_artistesDistinctsSansRapport_retourneFalse() {
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("radiohead", "coldplay"));
  }

  @Test
  void estDoublonArtiste_coTeteAffichePrefixeMotComplet_retourneTrue() {
    // ratio de containment 15/24 = 0.625 < 0.65, mais préfixe de mot complet valide
    assertTrue(
        CorrespondanceArtiste.estDoublonArtiste("earl sweatshirt", "earl sweatshirt and mike"));
  }

  @Test
  void estDoublonArtiste_suffixeTourneeApresPrefixe_retourneTrue() {
    assertTrue(CorrespondanceArtiste.estDoublonArtiste("jay z", "jay z 30"));
  }

  @Test
  void estDoublonArtiste_prefixeCoupeEnPleinMotSansEspace_retourneFalse() {
    // "earl sweat" continue en "sweatshirt" (pas d'espace) et le ratio 10/24 = 0.417 < 0.65
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("earl sweat", "earl sweatshirt and mike"));
  }

  @Test
  void estDoublonArtiste_prefixeUnSeulMot_retourneFalse() {
    assertFalse(CorrespondanceArtiste.estDoublonArtiste("mike", "mike tyson"));
  }

  @Test
  void estDoublonArtiste_premierParametreNull_leveNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> CorrespondanceArtiste.estDoublonArtiste(null, "x"));
  }

  @Test
  void estDoublonArtiste_deuxiemeParametreNull_leveNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> CorrespondanceArtiste.estDoublonArtiste("x", null));
  }

  // Paires réelles de la prod : même salle, date et heure, une fiche par billetterie.
  @Test
  void estMemeAffiche_suffixesEtCoPlateaux_retourneTrue() {
    for (final String[] paire :
        new String[][] {
          {"meryl", "meryl 1ere partie"},
          {"fredz", "fredz premiere partie"},
          {"celkilt", "celkilt support"},
          {"onslaught", "onslaught guest"},
          {"vivaldi", "antonio vivaldi"},
          {"behemoth", "behemoth and dimmu borgir"},
          {"le sserafim", "2026 le sserafim tour"},
          {"saez", "damien saez"},
          {
            "young thug",
            "young stoner life records and young thug presents the new generation tour"
          }
        }) {
      assertTrue(CorrespondanceArtiste.estMemeAffiche(paire[0], paire[1]), paire[0]);
      assertTrue(CorrespondanceArtiste.estMemeAffiche(paire[1], paire[0]), paire[1]);
    }
  }

  @Test
  void estMemeAffiche_motsDifferents_retourneFalse() {
    assertFalse(CorrespondanceArtiste.estMemeAffiche("mozart", "beethoven"));
    assertFalse(CorrespondanceArtiste.estMemeAffiche("les miserables", "casse noisette"));
  }

  @Test
  void estMemeAffiche_seulsDesMotsCourtsOuAccessoires_retourneFalse() {
    assertFalse(CorrespondanceArtiste.estMemeAffiche("d", "d and m"));
    assertFalse(CorrespondanceArtiste.estMemeAffiche("1ere partie", "dosseh 1ere partie"));
  }

  // Sidiki Diabaté : le titre Eventim ajoute « à l'Accor Arena » au nom de l'artiste.
  @Test
  void estNomSuiviDeLaSalle_nomPuisLiaisonEtSalle_retourneVrai() {
    assertTrue(
        CorrespondanceArtiste.estNomSuiviDeLaSalle(
            "sidiki diabate a l accor arena", "sidiki diabate", "accor arena paris bercy"));
  }

  @Test
  void estNomSuiviDeLaSalle_motsAjoutesQuiNeSontPasLaSalle_retourneFaux() {
    assertFalse(
        CorrespondanceArtiste.estNomSuiviDeLaSalle(
            "damien gillard rude", "damien gillard", "accor arena"));
  }

  @Test
  void estNomSuiviDeLaSalle_titreSansLeNom_retourneFaux() {
    assertFalse(
        CorrespondanceArtiste.estNomSuiviDeLaSalle(
            "autre artiste a l accor arena", "sidiki diabate", "accor arena"));
  }

  @Test
  void estNomSuiviDeLaSalle_parametresAbsents_retourneFaux() {
    assertFalse(CorrespondanceArtiste.estNomSuiviDeLaSalle("a b", "a", null));
    assertFalse(CorrespondanceArtiste.estNomSuiviDeLaSalle(null, "a", "b"));
  }
}
