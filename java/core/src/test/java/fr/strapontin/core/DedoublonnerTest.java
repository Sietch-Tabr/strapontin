package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DedoublonnerTest {

  private static ConcertBrut concert(String artiste, String date, String salle) {
    return new ConcertBrut(
        artiste,
        date,
        null,
        salle,
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
        null);
  }

  private static ConcertBrut concert(String artiste, String date, String salle, String ville) {
    return new ConcertBrut(
        artiste,
        date,
        null,
        salle,
        ville,
        null,
        null,
        null,
        null,
        "ticketmaster",
        null,
        null,
        null,
        null,
        null);
  }

  private static ConcertBrut concert(
      String artiste, String date, String salle, String ville, String source) {
    return new ConcertBrut(
        artiste, date, null, salle, ville, null, null, null, null, source, null, null, null, null,
        null);
  }

  // --- Cas limites ---

  @Test
  void dedoublonner_listeVide_retourneListeVide() {
    assertEquals(List.of(), Dedoublonner.dedoublonner(List.of()));
  }

  @Test
  void dedoublonner_concertUnique_retourneUnElement() {
    var concerts = List.of(concert("Radiohead", "2025-06-15", "Zénith Paris"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_concertUnique_conserveTousLesChamps() {
    var c =
        new ConcertBrut(
            "Radiohead",
            "2025-06-15",
            "20:00",
            "Zénith Paris",
            "Paris",
            "75",
            35.0,
            65.0,
            "https://example.com",
            "ticketmaster",
            "music",
            "Rock",
            null,
            null,
            null);
    var resultat = Dedoublonner.dedoublonner(List.of(c));
    assertEquals(1, resultat.size());
    var r = resultat.get(0);
    assertEquals("Radiohead", r.artiste());
    assertEquals("Paris", r.ville());
    assertEquals(35.0, r.prixMin());
    assertEquals("https://example.com", r.url());
  }

  // --- Passe exacte ---

  @Test
  void dedoublonner_passeExacte_fusionneDeuxConcertsMemeDateVille() {
    var concerts =
        List.of(
            concert("Beyoncé", "2025-07-10", "Stade de France", "Paris", "ticketmaster"),
            concert("Beyoncé", "2025-07-10", "Stade de France", "Paris", "eventim"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeExacte_nePasseDeuxArtistesDistincts() {
    var concerts =
        List.of(
            concert("Radiohead", "2025-06-15", "Zénith Paris", "Paris"),
            concert("Coldplay", "2025-06-15", "Accor Arena", "Paris"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeExacte_choisitPlusComplet() {
    var complet =
        new ConcertBrut(
            "Daft Punk",
            "2025-08-20",
            "21:00",
            "Zénith Paris",
            "Paris",
            null,
            45.0,
            null,
            "https://example.com",
            "eventim",
            null,
            null,
            null,
            null,
            null);
    var incomplet = concert("Daft Punk", "2025-08-20", "Zénith Paris", "Paris", "ticketmaster");
    var resultat = Dedoublonner.dedoublonner(List.of(incomplet, complet));
    assertEquals(1, resultat.size());
    assertEquals("https://example.com", resultat.get(0).url());
    assertEquals(45.0, resultat.get(0).prixMin());
  }

  @Test
  void dedoublonner_passeExacte_prefereDiacritiquesACompleteEgale() {
    var avecAccent = concert("Julien Doré", "2025-09-01", "Olympia", "Paris", "eventim");
    var sansAccent = concert("Julien Dore", "2025-09-01", "Olympia", "Paris", "ticketmaster");
    var resultat = Dedoublonner.dedoublonner(List.of(sansAccent, avecAccent));
    assertEquals(1, resultat.size());
    assertEquals("Julien Doré", resultat.get(0).artiste());
  }

  // --- Passe fuzzy ---

  @Test
  void dedoublonner_passeFuzzy_fusionne2NomsSimilaires() {
    // "julien clerc" vs "julien clere" — distance 1, sim ~0.917
    var concerts =
        List.of(
            concert("Julien Clerc", "2025-10-05", "Casino de Paris", "Paris", "ticketmaster"),
            concert("Julien Clere", "2025-10-05", "Casino de Paris", "Paris", "bandsintown"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeFuzzy_nePasseBeatlesVsTheBeatles() {
    // "beatles" vs "the beatles" — distance 4, sim ≈ 0.636
    var concerts =
        List.of(
            concert("The Beatles", "2025-11-01", "Olympia", "Paris", "ticketmaster"),
            concert("Beatles", "2025-11-01", "Olympia", "Paris", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_passeFuzzy_nePasseArtistesDistincts() {
    var concerts =
        List.of(
            concert("Radiohead", "2025-11-12", "Accor Arena", "Paris", "ticketmaster"),
            concert("Coldplay", "2025-11-12", "Stade de France", "Paris", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // --- Fallback salle ---

  @Test
  void dedoublonner_fallbackSalle_fusionneVillesDivergentes() {
    // Stade de France : TM donne Paris, Eventim donne Saint-Denis
    var concerts =
        List.of(
            concert("Beyoncé", "2025-06-15", "Stade de France", "Paris", "ticketmaster"),
            concert("Beyoncé", "2025-06-15", "Stade de France", "Saint-Denis", "eventim"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_fallbackSalle_nePasseArtistesDistincts() {
    var concerts =
        List.of(
            concert("Radiohead", "2025-06-15", "Stade de France", "Paris", "ticketmaster"),
            concert("Coldplay", "2025-06-15", "Stade de France", "Saint-Denis", "eventim"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }

  // --- Sélection de salle dans fusionner ---

  @Test
  void dedoublonner_passeExacte_prefereSalleAvecDiacritiques() {
    var avecAccent = concert("Mika", "2025-10-01", "Zénith Paris", "Paris", "eventim");
    var sansAccent = concert("Mika", "2025-10-01", "ZENITH PARIS", "Paris", "ticketmaster");
    var resultat = Dedoublonner.dedoublonner(List.of(sansAccent, avecAccent));
    assertEquals(1, resultat.size());
    assertEquals("Zénith Paris", resultat.get(0).salle());
  }

  @Test
  void dedoublonner_passeExacte_prefereSalleAvecCasseMixte() {
    var casseMixte = concert("Stromae", "2025-11-05", "Accor Arena", "Paris", "eventim");
    var toutMajuscules = concert("Stromae", "2025-11-05", "ACCOR ARENA", "Paris", "ticketmaster");
    var resultat = Dedoublonner.dedoublonner(List.of(toutMajuscules, casseMixte));
    assertEquals(1, resultat.size());
    assertEquals("Accor Arena", resultat.get(0).salle());
  }

  // --- Ville null ---

  @Test
  void dedoublonner_villeNull_fusionneMemeSalle() {
    // ville null → ville_norm "" → même clé → fusionné
    var concerts =
        List.of(
            concert("Portishead", "2025-05-20", "La Cigale", null, "bandsintown"),
            concert("Portishead", "2025-05-20", "La Cigale", null, "ticketmaster"));
    assertEquals(1, Dedoublonner.dedoublonner(concerts).size());
  }

  @Test
  void dedoublonner_villeNull_nePasseSallesDifferentes() {
    var concerts =
        List.of(
            concert("Portishead", "2025-05-20", "La Cigale", null, "bandsintown"),
            concert("Portishead", "2025-05-21", "Olympia", null, "ticketmaster"));
    assertEquals(2, Dedoublonner.dedoublonner(concerts).size());
  }
}
