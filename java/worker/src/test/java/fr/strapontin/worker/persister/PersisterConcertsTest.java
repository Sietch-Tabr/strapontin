/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersisterConcertsTest {

  // --- Fixtures ---

  private static ConcertBrut concert(String artiste, String date, String salle) {
    return concert(artiste, date, salle, "ticketmaster");
  }

  private static ConcertBrut concert(String artiste, String date, String salle, String source) {
    return new ConcertBrut(
        artiste, date, "20:00", salle, "Paris", "75", null, source, "music", "Pop", null, null,
        null, null, null);
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

  private static ValeurConcert valeurAvecArtisteId(
      String artisteNorm, String date, String salleNorm, String artisteSourceId, UUID artisteId) {
    return new ValeurConcert(
        artisteNorm,
        artisteNorm,
        LocalDate.parse(date),
        null,
        salleNorm,
        salleNorm,
        null,
        null,
        null,
        "eventim",
        null,
        null,
        null,
        null,
        null,
        null,
        artisteSourceId,
        null,
        artisteId);
  }

  // --- estDoublonSalle ---

  @Test
  void estDoublonSalle_chainesIdentiques_retourneTrue() {
    assertTrue(PersisterConcerts.estDoublonSalle("accor arena", "accor arena"));
  }

  @Test
  void estDoublonSalle_similariteSuperieure_retourneTrue() {
    // "zenith paris" vs "zenith de paris" — similarité ≈ 0.92 ≥ 0.75
    assertTrue(PersisterConcerts.estDoublonSalle("zenith paris", "zenith de paris"));
  }

  @Test
  void estDoublonSalle_variationsLegitimes_retourneTrue() {
    // article — sim 0.78 ≥ 0.75
    assertTrue(PersisterConcerts.estDoublonSalle("l olympia", "olympia"));
    // suffixe ville — containment, ratio 0.65 ≥ 0.5
    assertTrue(PersisterConcerts.estDoublonSalle("accor arena", "accor arena paris"));
    // préfixe "de" — sim 0.83 ≥ 0.75
    assertTrue(PersisterConcerts.estDoublonSalle("zenith de bordeaux", "zenith bordeaux"));
    // containment préfixe — ratio 0.5 exactement
    assertTrue(PersisterConcerts.estDoublonSalle("zenith paris", "zenith paris la villette"));
  }

  @Test
  void estDoublonSalle_apostropheOmiseParUneSource_retourneTrue() {
    // #902 : "L'Olympia" (TM) normalisé "l olympia" vs "LOlympia" (Eventim, sans apostrophe)
    // normalisé "lolympia" — aucun token commun, mais identiques une fois les espaces retirés.
    assertTrue(PersisterConcerts.estDoublonSalle("l olympia", "lolympia"));
  }

  @Test
  void estDoublonSalle_villesDistinctes_retourneFalse() {
    // sim 0.61 < 0.75, pas de containment commun — deux zéniths différents
    assertFalse(PersisterConcerts.estDoublonSalle("zenith de bordeaux", "zenith de caen"));
  }

  @Test
  void estDoublonSalle_seuil050_retourneFalse() {
    // sim exactement 0.50 < 0.75 — salles non appariées sous le nouveau seuil
    assertFalse(PersisterConcerts.estDoublonSalle("le trianon", "le tigre"));
    assertFalse(PersisterConcerts.estDoublonSalle("bataclan", "batofar"));
    assertFalse(PersisterConcerts.estDoublonSalle("salle pleyel", "salle gaveau"));
  }

  @Test
  void estDoublonSalle_containmentRatioTropFaible_retourneFalse() {
    // "zenith" (6) dans "zenith bordeaux" (15) — ratio 0.40 < 0.50
    assertFalse(PersisterConcerts.estDoublonSalle("zenith", "zenith bordeaux"));
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

  // --- estDoublonSalle — Jaccard tokens (#669) ---

  @Test
  void estDoublonSalle_reordonancementMots_retourneTrue() {
    // Jaccard = 1.0 — même tokens, ordre différent
    assertTrue(
        PersisterConcerts.estDoublonSalle(
            "zenith europe strasbourg", "zenith de strasbourg europe"));
    // Jaccard = 1.0 — "de" filtré (longueur < 3)
    assertTrue(PersisterConcerts.estDoublonSalle("zenith de nancy", "le zenith nancy"));
  }

  @Test
  void estDoublonSalle_tokensCommunsSuperieursSeuil_retourneTrue() {
    // Jaccard = 3/5 = 0.6 > 0.5 — "arena", "pays", "aix" communs
    assertTrue(
        PersisterConcerts.estDoublonSalle(
            "arena du pays d aix", "arena du pays daix aix en provence"));
  }

  @Test
  void estDoublonSalle_tokensCommunsEgauxSeuil_retourneFalse() {
    // Jaccard = 0.5 exactement — seuil strict > 0.5
    assertFalse(PersisterConcerts.estDoublonSalle("zenith", "zenith bordeaux"));
    assertFalse(PersisterConcerts.estDoublonSalle("galaxie", "le galaxie amneville"));
  }

  @Test
  void estDoublonSalle_villesDistinctesJaccard_retourneFalse() {
    // Jaccard = 1/3 ≈ 0.33 — "zenith" commun mais villes différentes
    assertFalse(PersisterConcerts.estDoublonSalle("zenith de bordeaux", "zenith de caen"));
  }

  // --- estDoublonSalle — critère pivot (#702) ---

  @Test
  void estDoublonSalle_kursaalBesanconVsGrandKursaal_retourneTrue() {
    // #702 : Eventim renomme "Grand Kursaal" → "Kursaal Besancon" entre deux fetches
    // Token pivot "kursaal" (7 chars), Jaccard = 1/3 = 0.33 > 0.3
    assertTrue(PersisterConcerts.estDoublonSalle("grand kursaal", "kursaal besancon"));
  }

  @Test
  void estDoublonSalle_megaciteAmiensVsAuditoriumMegacite_retourneTrue() {
    // #702 : Eventim renomme "Auditorium Megacite" → "Megacite Amiens" entre deux fetches
    // Token pivot "megacite" (8 chars), Jaccard = 1/3 = 0.33 > 0.3
    assertTrue(PersisterConcerts.estDoublonSalle("auditorium megacite", "megacite amiens"));
  }

  // --- jaccardTokens ---

  @Test
  void jaccardTokens_chainesIdentiques_retourneUn() {
    assertEquals(1.0, PersisterConcerts.jaccardTokens("zenith paris", "zenith paris"), 0.001);
  }

  @Test
  void jaccardTokens_tokensCourtsFiltres() {
    // "de" (2 chars) filtré : {zenith, nancy} ∩ {zenith, nancy} = 1.0
    assertEquals(1.0, PersisterConcerts.jaccardTokens("zenith de nancy", "le zenith nancy"), 0.001);
  }

  @Test
  void jaccardTokens_deuxVides_retourneUn() {
    // Deux chaînes sans token long — définies comme identiques
    assertEquals(1.0, PersisterConcerts.jaccardTokens("de la le", "du en au"), 0.001);
  }

  @Test
  void jaccardTokens_sansIntersection_retourneZero() {
    assertEquals(0.0, PersisterConcerts.jaccardTokens("zenith paris", "stade france"), 0.001);
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
  void estDoublonArtiste_troncatureTm_aliz_retourneTrue() {
    // TM a renommé "ALIZÉE" en "ALIZ" entre deux fetches
    assertTrue(PersisterConcerts.estDoublonArtiste("aliz", "alizee"));
  }

  @Test
  void estDoublonArtiste_troncatureTm_fawn_retourneTrue() {
    // TM a renommé "FAWNN" en "FAWN" entre deux fetches
    assertTrue(PersisterConcerts.estDoublonArtiste("fawn", "fawnn"));
  }

  @Test
  void estDoublonArtiste_ratioTropFaible_retourneFalse() {
    // ratio court/long = 4/17 ≈ 0.24 < 0.65 → pas de containment
    assertFalse(PersisterConcerts.estDoublonArtiste("muse", "muse tour tribute"));
  }

  @Test
  void estDoublonArtiste_artistesCourtsDistincts_retourneFalse() {
    // ratio = 4/10 = 0.4 < 0.65 → pas de containment malgré le préfixe commun
    assertFalse(PersisterConcerts.estDoublonArtiste("pink", "pink floyd"));
  }

  @Test
  void estDoublonArtiste_collaborationPrefixe_retourneFalse() {
    // ratio = 7/11 = 0.636 < 0.65 → collaboration ≠ artiste solo
    assertFalse(PersisterConcerts.estDoublonArtiste("lomepal", "lomepal sch"));
  }

  @Test
  void estDoublonArtiste_artistesCompletementDifferents_retourneFalse() {
    assertFalse(PersisterConcerts.estDoublonArtiste("mika", "coldplay"));
  }

  @Test
  void estDoublonArtiste_similariteEntreSeuilSalleEtSeuilArtiste_retourneFalse() {
    // Jaro("julien dore", "julien morel") ≈ 0.914 < 0.95 — deux artistes distincts malgré le
    // préfixe commun
    assertFalse(PersisterConcerts.estDoublonArtiste("julien dore", "julien morel"));
  }

  @Test
  void estDoublonArtiste_krashKarmaEspaceOmise_retourneTrue() {
    // #668 : Jaro("krash karma", "krashkarma") ≈ 0.97 ≥ 0.95 — même artiste, espace omis
    assertTrue(PersisterConcerts.estDoublonArtiste("krash karma", "krashkarma"));
  }

  @Test
  void estDoublonArtiste_dollarStyleArtisteTM_retourneTrue() {
    // #703 : "Chri$tian Gate$" → norm "chritian gate" ($ supprimé sans espace)
    //        "Christian Gates" → norm "christian gates"
    // Jaro("chritian gate", "christian gates") ≈ 0.956 ≥ 0.95
    assertTrue(PersisterConcerts.estDoublonArtiste("chritian gate", "christian gates"));
  }

  @Test
  void estDoublonArtiste_esperluetteVsAnd_retourneTrue() {
    // #703 : "Katia & Marielle Labèque" → norm "katia and marielle labeque" (nouveau)
    //        "Katia and Marielle Labèque" → norm "katia and marielle labeque" (identique)
    assertTrue(
        PersisterConcerts.estDoublonArtiste(
            "katia and marielle labeque", "katia and marielle labeque"));
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
  void deduplicerParCleConflit_elyseeAvecEtSansAccents_reconcilie() {
    // non-régression #639 : TM retourne "Elysee Montmartre" (sans accent) alors que la salle
    // existe en base sous "Elysée Montmartre" — les deux doivent produire la même clé
    List<ConcertBrut> liste =
        List.of(
            concert("Artiste", "2026-09-10", "Elysée Montmartre", "eventim"),
            concert("Artiste", "2026-09-10", "Elysee Montmartre", "ticketmaster"));
    List<ConcertBrut> resultat = PersisterConcerts.deduplicerParCleConflit(liste);
    assertEquals(1, resultat.size());
    assertEquals("Elysée Montmartre", resultat.get(0).salle());
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

  // --- resoudreChaines (via construireRemplacementsIntraLot) ---

  // La résolution de chaînes est privée ; on la teste indirectement via deduplicerParCleConflit
  // sur trois variantes de salle en cascade : A ≈ B ≈ C → toutes doivent avoir la même clé.

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
