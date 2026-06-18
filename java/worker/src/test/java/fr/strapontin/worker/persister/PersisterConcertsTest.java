/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersisterConcertsTest {

  // --- Fixtures ---

  private static ConcertBrut concert(String artiste, String date, String salle) {
    return concert(artiste, date, salle, "ticketmaster");
  }

  private static ConcertBrut concert(String artiste, String date, String salle, String source) {
    return new ConcertBrut(
        artiste, date, "20:00", salle, "Paris", "75", null, null, null, source, "music", "Pop",
        null, null, null, null, null);
  }

  private static ValeurConcert valeur(
      String artisteNorm, String date, String salleNorm, String artiste, String salle) {
    return new ValeurConcert(
        artiste,
        artisteNorm,
        LocalDate.parse(date),
        null,
        salle,
        salleNorm,
        null,
        null,
        null,
        null,
        null,
        "ticketmaster",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ValeurConcert valeur(String artisteNorm, String date, String salleNorm) {
    return valeur(artisteNorm, date, salleNorm, artisteNorm, salleNorm);
  }

  // --- estDoublonSalle ---

  @Test
  void estDoublonSalle_chainesIdentiques_retourneTrue() {
    assertTrue(PersisterConcerts.estDoublonSalle("accor arena", "accor arena"));
  }

  @Test
  void estDoublonSalle_similariteSuperieure_retourneTrue() {
    // "zenith paris" vs "zenith de paris" — similarité ≈ 0.92
    assertTrue(PersisterConcerts.estDoublonSalle("zenith paris", "zenith de paris"));
  }

  @Test
  void estDoublonSalle_containmentCourteChaine4CharsMin_retourneTrue() {
    assertTrue(PersisterConcerts.estDoublonSalle("olymp", "olympia"));
    assertTrue(PersisterConcerts.estDoublonSalle("olympia", "grand olympia de paris"));
  }

  @Test
  void estDoublonSalle_containmentChaineInferieure4Chars_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonSalle("oly", "olympia"));
  }

  @Test
  void estDoublonSalle_sallesComplètementDifferentes_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonSalle("zenith paris", "stade de france"));
    assertFalse(PersisterConcerts.estDoublonSalle("la cigale", "accor arena"));
  }

  @Test
  void estDoublonSalle_deuxChainesVides_retourneTrue() {
    assertTrue(PersisterConcerts.estDoublonSalle("", ""));
  }

  @Test
  void estDoublonSalle_casseDifferenteSansSimilariteSuffisante_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonSalle("accor arena", "ACCOR ARENA"));
  }

  // --- estDoublonArtiste ---

  @Test
  void estDoublonArtiste_chainesIdentiques_retourneTrue() {
    assertTrue(PersisterConcerts.estDoublonArtiste("star academy", "star academy"));
  }

  @Test
  void estDoublonArtiste_suffixeNumerique_retourneTrue() {
    // ticket #619 — "Star Academy" -> "Star Academy 1" entre deux fetches TM
    assertTrue(PersisterConcerts.estDoublonArtiste("star academy", "star academy 1"));
  }

  @Test
  void estDoublonArtiste_articleAjoute_retourneTrue() {
    // ticket #619 — "Comédies Musicales" -> "Les Comédies Musicales" entre deux fetches TM
    assertTrue(PersisterConcerts.estDoublonArtiste("comedies musicales", "les comedies musicales"));
  }

  @Test
  void estDoublonArtiste_containmentChaineInferieure10Chars_retourneFalse() {
    // garde contre les faux positifs : éviter de fusionner "Muse" avec un nom proche plus long
    assertFalse(PersisterConcerts.estDoublonArtiste("muse", "muse tour tribute"));
  }

  @Test
  void estDoublonArtiste_artistesCourtsDistincts_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonArtiste("pink", "pink floyd"));
  }

  @Test
  void estDoublonArtiste_artistesCompletementDifferents_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonArtiste("mika", "coldplay"));
  }

  @Test
  void estDoublonArtiste_similariteEntreSeuilSalleEtSeuilArtiste_retourneFalse() {
    // similarité ≈ 0.83 : passerait le seuil salle (0.5) mais pas le seuil artiste (0.85)
    assertFalse(PersisterConcerts.estDoublonArtiste("julien dore", "julien morel"));
  }

  // --- deduplicerParCleConflit ---

  @Test
  void deduplicerParCleConflit_concertsIdentiques_retourneUnSeul() {
    List<ConcertBrut> liste =
        List.of(
            concert("Mika", "2026-06-01", "Accor Arena"),
            concert("Mika", "2026-06-01", "Accor Arena"));
    assertEquals(1, PersisterConcerts.deduplicerParCleConflit(liste).size());
  }

  @Test
  void deduplicerParCleConflit_resolverCreDoublon_conserveVersionAvecDiacritiques() {
    List<ConcertBrut> liste =
        List.of(
            concert("MIKA", "2026-06-01", "Accor Arena", "ticketmaster"),
            concert("Mika", "2026-06-01", "Accor Arena", "eventim"));
    List<ConcertBrut> resultat = PersisterConcerts.deduplicerParCleConflit(liste);
    assertEquals(1, resultat.size());
    assertEquals("Mika", resultat.get(0).artiste());
  }

  @Test
  void deduplicerParCleConflit_salleAvecAccentEtSans_conserveAccents() {
    List<ConcertBrut> liste =
        List.of(
            concert("Amel Bent", "2026-05-10", "Zenith de Dijon", "ticketmaster"),
            concert("Amel Bent", "2026-05-10", "Zénith de Dijon", "eventim"));
    List<ConcertBrut> resultat = PersisterConcerts.deduplicerParCleConflit(liste);
    assertEquals(1, resultat.size());
    assertEquals("Zénith de Dijon", resultat.get(0).salle());
  }

  @Test
  void deduplicerParCleConflit_datesDifferentes_conserveLesDeuxConcerts() {
    List<ConcertBrut> liste =
        List.of(
            concert("Mika", "2026-06-01", "Accor Arena"),
            concert("Mika", "2026-06-02", "Accor Arena"));
    assertEquals(2, PersisterConcerts.deduplicerParCleConflit(liste).size());
  }

  @Test
  void deduplicerParCleConflit_artistesDifferents_conserveLesDeuxConcerts() {
    List<ConcertBrut> liste =
        List.of(
            concert("Mika", "2026-06-01", "Accor Arena"),
            concert("Coldplay", "2026-06-01", "Accor Arena"));
    assertEquals(2, PersisterConcerts.deduplicerParCleConflit(liste).size());
  }

  @Test
  void deduplicerParCleConflit_listeVide_retourneVide() {
    assertEquals(0, PersisterConcerts.deduplicerParCleConflit(List.of()).size());
  }

  // --- deduplicerLotReconcilie ---

  @Test
  void deduplicerLotReconcilie_listeVide_retourneVide() {
    assertEquals(0, PersisterConcerts.deduplicerLotReconcilie(List.of()).size());
  }

  @Test
  void deduplicerLotReconcilie_clesDifferentes_conserveLesDeuxConcerts() {
    List<ValeurConcert> lot =
        List.of(
            valeur("mika", "2026-06-01", "accor arena"),
            valeur("coldplay", "2026-06-01", "stade de france"));
    assertEquals(2, PersisterConcerts.deduplicerLotReconcilie(lot).size());
  }

  @Test
  void deduplicerLotReconcilie_doublonMemeCle_conserveVersionAvecDiacritiquesArtiste() {
    List<ValeurConcert> lot =
        List.of(
            valeur("julien dore", "2026-05-10", "olympia", "Julien Dore", "Olympia"),
            valeur("julien dore", "2026-05-10", "olympia", "Julien Doré", "Olympia"));
    List<ValeurConcert> resultat = PersisterConcerts.deduplicerLotReconcilie(lot);
    assertEquals(1, resultat.size());
    assertEquals("Julien Doré", resultat.get(0).artiste());
  }

  @Test
  void deduplicerLotReconcilie_doublonMemeCle_conserveVersionAvecCasseMixteSalle() {
    List<ValeurConcert> lot =
        List.of(
            valeur("mika", "2026-07-01", "zenith paris", "Mika", "ZENITH PARIS"),
            valeur("mika", "2026-07-01", "zenith paris", "Mika", "Zénith Paris"));
    List<ValeurConcert> resultat = PersisterConcerts.deduplicerLotReconcilie(lot);
    assertEquals(1, resultat.size());
    assertEquals("Zénith Paris", resultat.get(0).salle());
  }
}
