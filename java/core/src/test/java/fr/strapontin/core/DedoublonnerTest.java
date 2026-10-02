/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DedoublonnerTest {

  private static ConcertBrut concert(final String artiste, final String date, final String salle) {
    return ConcertBrut.nouveau()
        .artiste(artiste)
        .date(date)
        .salle(salle)
        .source("ticketmaster")
        .construire();
  }

  private static ConcertBrut concert(
      final String artiste, final String date, final String salle, final String ville) {
    return ConcertBrut.nouveau()
        .artiste(artiste)
        .date(date)
        .salle(salle)
        .ville(ville)
        .source("ticketmaster")
        .construire();
  }

  private static ConcertBrut concert(
      final String artiste,
      final String date,
      final String salle,
      final String ville,
      final String source) {
    return ConcertBrut.nouveau()
        .artiste(artiste)
        .date(date)
        .salle(salle)
        .ville(ville)
        .source(source)
        .construire();
  }

  // --- Cas limites ---

  @Test
  void dedoublonner_listeVide_retourneListeVide() {
    assertEquals(List.of(), Dedoublonner.dedoublonner(List.of()));
  }

  @Test
  void dedoublonner_concertUnique_retourneUnElement() {
    final var concerts = List.of(concert("Radiohead", "2025-06-15", "Zénith Paris"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_concertUnique_conserveTousLesChamps() {
    final var c =
        ConcertBrut.nouveau()
            .artiste("Radiohead")
            .date("2025-06-15")
            .heure("20:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .dept("75")
            .url("https://example.com")
            .source("ticketmaster")
            .classification("music")
            .genre("Rock")
            .construire();
    final var resultat = Dedoublonner.dedoublonner(List.of(c));
    assertEquals(1, resultat.size());
    final var r = resultat.get(0);
    assertEquals("Radiohead", r.artiste());
    assertEquals("Paris", r.ville());
    assertEquals("https://example.com", r.url());
  }

  // --- Passe exacte ---

  @Test
  void dedoublonner_passeExacte_fusionneDeuxConcertsMemeDateVille() {
    final var concerts =
        List.of(
            concert("Beyoncé", "2025-07-10", "Stade de France", "Paris", "ticketmaster"),
            concert("Beyoncé", "2025-07-10", "Stade de France", "Paris", "eventim"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeExacte_nePasseDeuxArtistesDistincts() {
    final var concerts =
        List.of(
            concert("Radiohead", "2025-06-15", "Zénith Paris", "Paris"),
            concert("Coldplay", "2025-06-15", "Accor Arena", "Paris"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeExacte_choisitPlusComplet() {
    final var complet =
        ConcertBrut.nouveau()
            .artiste("Daft Punk")
            .date("2025-08-20")
            .heure("21:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .url("https://example.com")
            .source("eventim")
            .construire();
    final var incomplet =
        concert("Daft Punk", "2025-08-20", "Zénith Paris", "Paris", "ticketmaster");
    final var resultat = Dedoublonner.dedoublonner(List.of(incomplet, complet));
    assertEquals(1, resultat.size());
    assertEquals("https://example.com", resultat.get(0).url());
  }

  @Test
  void dedoublonner_passeExacte_prefereDiacritiquesACompleteEgale() {
    final var avecAccent = concert("Julien Doré", "2025-09-01", "Olympia", "Paris", "eventim");
    final var sansAccent = concert("Julien Dore", "2025-09-01", "Olympia", "Paris", "ticketmaster");
    final var resultat = Dedoublonner.dedoublonner(List.of(sansAccent, avecAccent));
    assertEquals(1, resultat.size());
    assertEquals("Julien Doré", resultat.get(0).artiste());
  }

  @Test
  void dedoublonner_completudeEgaleStatutConnuDuPerdantOrdreInitial_conserveStatut() {
    // Champs identiques hors statut/source : sans le point de complétude dédié au statut,
    // le départage tombe sur les diacritiques (égaux ici) et retient le premier élément de la
    // liste quel que soit son statut — placer le concert sans statut en tête reproduit le biais
    // d'ordre du bug.
    final var sansStatut =
        ConcertBrut.nouveau()
            .artiste("Angele")
            .date("2026-11-06")
            .heure("20:30")
            .salle("Zenith")
            .ville("Paris")
            .dept("75")
            .url("https://example.com")
            .source("eventim")
            .classification("music")
            .genre("Pop")
            .construire();
    final var avecStatut =
        ConcertBrut.nouveau()
            .artiste("Angele")
            .date("2026-11-06")
            .heure("20:30")
            .salle("Zenith")
            .ville("Paris")
            .dept("75")
            .url("https://example.com")
            .source("ticketmaster")
            .classification("music")
            .genre("Pop")
            .statut("presale")
            .construire();

    final var resultat = Dedoublonner.dedoublonner(List.of(sansStatut, avecStatut));

    assertEquals(1, resultat.size());
    assertEquals("presale", resultat.get(0).statut());
  }

  // --- Passe fuzzy ---

  @Test
  void dedoublonner_passeFuzzy_fusionne2NomsSimilaires() {
    // "julien clerc" vs "julien clere" — distance 1, sim ~0.917
    final var concerts =
        List.of(
            concert("Julien Clerc", "2025-10-05", "Casino de Paris", "Paris", "ticketmaster"),
            concert("Julien Clere", "2025-10-05", "Casino de Paris", "Paris", "bandsintown"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeFuzzy_nePasseBeatlesVsTheBeatles() {
    // "beatles" vs "the beatles" — distance 4, sim ≈ 0.636
    final var concerts =
        List.of(
            concert("The Beatles", "2025-11-01", "Olympia", "Paris", "ticketmaster"),
            concert("Beatles", "2025-11-01", "Olympia", "Paris", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeFuzzy_nePasseArtistesDistincts() {
    final var concerts =
        List.of(
            concert("Radiohead", "2025-11-12", "Accor Arena", "Paris", "ticketmaster"),
            concert("Coldplay", "2025-11-12", "Stade de France", "Paris", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // --- Fallback salle ---

  @Test
  void dedoublonner_fallbackSalle_fusionneVillesDivergentes() {
    // Stade de France : TM donne Paris, Eventim donne Saint-Denis
    final var concerts =
        List.of(
            concert("Beyoncé", "2025-06-15", "Stade de France", "Paris", "ticketmaster"),
            concert("Beyoncé", "2025-06-15", "Stade de France", "Saint-Denis", "eventim"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_fallbackSalle_nePasseArtistesDistincts() {
    final var concerts =
        List.of(
            concert("Radiohead", "2025-06-15", "Stade de France", "Paris", "ticketmaster"),
            concert("Coldplay", "2025-06-15", "Stade de France", "Saint-Denis", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // --- Sélection de salle dans fusionner ---

  @Test
  void dedoublonner_passeExacte_prefereSalleAvecDiacritiques() {
    final var avecAccent = concert("Mika", "2025-10-01", "Zénith Paris", "Paris", "eventim");
    final var sansAccent = concert("Mika", "2025-10-01", "ZENITH PARIS", "Paris", "ticketmaster");
    final var resultat = Dedoublonner.dedoublonner(List.of(sansAccent, avecAccent));
    assertEquals(1, resultat.size());
    assertEquals("Zénith Paris", resultat.get(0).salle());
  }

  @Test
  void dedoublonner_passeExacte_prefereSalleAvecCasseMixte() {
    final var casseMixte = concert("Stromae", "2025-11-05", "Accor Arena", "Paris", "eventim");
    final var toutMajuscules =
        concert("Stromae", "2025-11-05", "ACCOR ARENA", "Paris", "ticketmaster");
    final var resultat = Dedoublonner.dedoublonner(List.of(toutMajuscules, casseMixte));
    assertEquals(1, resultat.size());
    assertEquals("Accor Arena", resultat.get(0).salle());
  }

  // --- Ville null ---

  @Test
  void dedoublonner_villeNull_fusionneMemeSalle() {
    // ville null des deux côtés, même salle → repli sur salle_norm comme clé → fusionné
    final var concerts =
        List.of(
            concert("Portishead", "2025-05-20", "La Cigale", null, "bandsintown"),
            concert("Portishead", "2025-05-20", "La Cigale", null, "ticketmaster"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_villeNull_nePasseSallesDifferentes() {
    final var concerts =
        List.of(
            concert("Portishead", "2025-05-20", "La Cigale", null, "bandsintown"),
            concert("Portishead", "2025-05-21", "Olympia", null, "ticketmaster"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // deux concerts du même artiste, même date, même ville, mais aucune ville renseignée
  // (null) et deux salles réellement différentes ne doivent pas fusionner — avant le correctif,
  // la clé de la passe exacte utilisait "" pour toute ville absente, fusionnant à tort deux
  // concerts distincts faute de salle dans la clé.
  @Test
  void dedoublonner_villeNullMemeDateSallesDifferentes_nePasseFusionner() {
    final var concerts =
        List.of(
            concert("Portishead", "2025-05-20", "La Cigale", null, "bandsintown"),
            concert("Portishead", "2025-05-20", "Olympia", null, "ticketmaster"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // --- Heure ---

  @Test
  void dedoublonner_matineeEtSoiree_nePasseFusionnerHeuresDifferentes() {
    final var matinee =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .heure("15:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("ticketmaster")
            .construire();
    final var soiree =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .heure("20:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("eventim")
            .construire();
    assertEquals(2, Dedoublonner.dedoublonner(List.of(matinee, soiree)).size());
  }

  // non-régression : deux fiches TM du même évènement réel (types "package"/"prestation")
  // décalées d'1 minute exacte doivent fusionner malgré l'écart d'heure.
  @Test
  void dedoublonner_heuresEcartUneMinute_fusionneMalgreEcart() {
    final var prestation =
        ConcertBrut.nouveau()
            .artiste("Baby Keem")
            .date("2026-09-03")
            .heure("20:31")
            .salle("L'Olympia")
            .ville("Paris")
            .source("ticketmaster")
            .construire();
    final var pack =
        ConcertBrut.nouveau()
            .artiste("Baby Keem")
            .date("2026-09-03")
            .heure("20:30")
            .salle("L'Olympia")
            .ville("Paris")
            .source("ticketmaster")
            .construire();
    assertEquals(1, Dedoublonner.dedoublonner(List.of(prestation, pack)).size());
  }

  @Test
  void dedoublonner_heuresEcartAuDelaDeLaTolerance_nePasseFusionner() {
    final var vingtHeures =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .heure("20:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("ticketmaster")
            .construire();
    final var vingtHeuresDix =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .heure("20:10")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("eventim")
            .construire();
    assertEquals(2, Dedoublonner.dedoublonner(List.of(vingtHeures, vingtHeuresDix)).size());
  }

  @Test
  void dedoublonner_heureAbsenteDunCote_fusionneMalgreTout() {
    final var sansHeure =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("ticketmaster")
            .construire();
    final var avecHeure =
        ConcertBrut.nouveau()
            .artiste("Comédie Musicale")
            .date("2025-06-15")
            .heure("20:00")
            .salle("Zénith Paris")
            .ville("Paris")
            .source("eventim")
            .construire();
    assertEquals(1, Dedoublonner.dedoublonner(List.of(sansHeure, avecHeure)).size());
  }
}
