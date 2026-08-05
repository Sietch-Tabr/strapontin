/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link DigitickSource}.
///
/// @since 2.4.0
/// @author Kwaite
class DigitickSourceTest {

  private static final String HTML_CONCERTS =
      """
      <html><body>
      <div class="evenementListe">
        <!-- Adhésion : genre Carte, pas de datetime — doit être filtrée -->
        <dl class="evenement1">
          <a class="evenementNom" href="https://web.digitick.com/adhesion-css5-lempreinte-pg51-ei000.html">Carte Adh&eacute;sion</a>
          <div itemprop="eventType" class="evenementGenre">Carte</div>
          <div class="evenementSalle">
            <span class="evenementSalleNom">L&#039;Empreinte</span>
            <span class="evenementSalleVille">- Savigny-Le-Temple</span>
            <span class="evenementSalleCodePostal">77176</span>
            <span class="evenementSalleVille">Savigny-Le-Temple</span>
          </div>
          <div class="evenementTarifsChoix">
            <a class="evenementReserver" href="https://web.digitick.com/adhesion-css5-lempreinte-pg51-ei000.html">Réserver</a>
          </div>
        </dl>
        <!-- Pack multi-dates : genre Concert, datetime présent, mais CP absent — doit être filtré -->
        <dl class="evenement2">
          <a class="evenementNom" href="https://web.digitick.com/pack-css5-lempreinte-pg51-ei001.html">Pack adh&eacute;sion + Concert</a>
          <div class="evenementDate">
            <span class="evenementJour">
              <time datetime="2026-04-25T20:00:00" itemprop="startDate">Du Samedi 25/04/2026 au Samedi 12/12/2026</time>
            </span>
          </div>
          <div itemprop="eventType" class="evenementGenre">Concert</div>
          <div class="evenementTarifsChoix">
            <a class="evenementReserver" href="https://web.digitick.com/pack-css5-lempreinte-pg51-ei001.html">Réserver</a>
          </div>
        </dl>
        <!-- Concert valide 1 -->
        <dl class="evenement3">
          <a class="evenementNom" href="https://web.digitick.com/ronnie-romero-css5-lempreinte-pg51-ei1101177.html">RONNIE ROMERO + GUS G</a>
          <div class="evenementDate">
            <span class="evenementJour">
              <time datetime="2026-10-07T20:00:00" itemprop="startDate">Mercredi 7 Octobre 2026 - 20h00</time>
            </span>
            <span class="evenementHeure">20h00</span>
          </div>
          <div itemprop="eventType" class="evenementGenre">Concert</div>
          <div class="evenementSalle">
            <span class="evenementSalleNom">L&#039;Empreinte</span>
            <span class="evenementSalleVille">- Savigny-Le-Temple</span>
            <span class="evenementSalleCodePostal">77176</span>
            <span class="evenementSalleVille">Savigny-Le-Temple</span>
          </div>
          <div class="evenementTarifsChoix">
            <a class="evenementReserver" href="https://web.digitick.com/ronnie-romero-css5-lempreinte-pg51-ei1101177.html">Réserver</a>
          </div>
        </dl>
        <!-- Concert valide 2 — heure à minuit → heure null -->
        <dl class="evenement4">
          <a class="evenementNom" href="https://web.digitick.com/laura-cox-css5-lempreinte-pg51-ei1050000.html">LAURA COX</a>
          <div class="evenementDate">
            <span class="evenementJour">
              <time datetime="2026-11-19T00:00:00" itemprop="startDate">Jeudi 19 Novembre 2026</time>
            </span>
          </div>
          <div itemprop="eventType" class="evenementGenre">Concert</div>
          <div class="evenementSalle">
            <span class="evenementSalleNom">L&#039;Empreinte</span>
            <span class="evenementSalleVille">- Savigny-Le-Temple</span>
            <span class="evenementSalleCodePostal">77176</span>
            <span class="evenementSalleVille">Savigny-Le-Temple</span>
          </div>
          <div class="evenementTarifsChoix">
            <a class="evenementReserver" href="https://web.digitick.com/laura-cox-css5-lempreinte-pg51-ei1050000.html">Réserver</a>
          </div>
        </dl>
      </div>
      </body></html>
      """;

  @Test
  void nom_retourneDigitick() {
    assertEquals("digitick", new DigitickSource().nom());
  }

  @Test
  void accepteIdentifiantSalle_prefixeValide_retourneVrai() {
    DigitickSource source = new DigitickSource();
    assertTrue(source.accepteIdentifiantSalle("digitick:lempreinte"));
    assertTrue(source.accepteIdentifiantSalle("digitick:a"));
  }

  @Test
  void accepteIdentifiantSalle_prefixeInvalide_retourneFaux() {
    DigitickSource source = new DigitickSource();
    assertFalse(source.accepteIdentifiantSalle("tm:rZFSnyZdFvA"));
    assertFalse(source.accepteIdentifiantSalle("eventim:123"));
    assertFalse(source.accepteIdentifiantSalle("lempreinte"));
    assertFalse(source.accepteIdentifiantSalle(null));
    assertFalse(source.accepteIdentifiantSalle(""));
  }

  @Test
  void parserConcerts_filtreLesNonConcerts_retourne2Concerts() {
    List<ConcertBrut> concerts = DigitickSource.parserConcerts(HTML_CONCERTS, "lempreinte");

    assertEquals(2, concerts.size());
  }

  @Test
  void parserConcerts_concertNominal_champsCorrects() {
    List<ConcertBrut> concerts = DigitickSource.parserConcerts(HTML_CONCERTS, "lempreinte");

    ConcertBrut concert = concerts.get(0);
    assertEquals("Ronnie Romero + Gus G", concert.artiste());
    assertEquals("2026-10-07", concert.date());
    assertEquals("20:00", concert.heure());
    assertEquals("L'Empreinte", concert.salle());
    assertEquals("Savigny-Le-Temple", concert.ville());
    assertEquals("77", concert.dept());
    assertEquals(
        "https://web.digitick.com/ronnie-romero-css5-lempreinte-pg51-ei1101177.html",
        concert.url());
    assertEquals("digitick", concert.source());
    assertEquals("digitick:lempreinte", concert.salleSourceId());
    assertNull(concert.artisteSourceId());
  }

  @Test
  void parserConcerts_heureMinuit_heureNull() {
    List<ConcertBrut> concerts = DigitickSource.parserConcerts(HTML_CONCERTS, "lempreinte");

    ConcertBrut concert = concerts.get(1);
    assertEquals("Laura Cox", concert.artiste());
    assertEquals("2026-11-19", concert.date());
    assertNull(concert.heure());
  }

  @Test
  void parserConcerts_htmlVide_retourneListeVide() {
    List<ConcertBrut> concerts =
        DigitickSource.parserConcerts("<html><body></body></html>", "slug");
    assertTrue(concerts.isEmpty());
  }

  @Test
  void parserDatetime_formatComplet_retourneDateEtHeure() {
    String[] resultat = DigitickSource.parserDatetime("2026-10-07T20:00:00");
    assertNotNull(resultat);
    assertEquals("2026-10-07", resultat[0]);
    assertEquals("20:00", resultat[1]);
  }

  @Test
  void parserDatetime_heureMinuit_retourneHeureNull() {
    String[] resultat = DigitickSource.parserDatetime("2026-11-19T00:00:00");
    assertNotNull(resultat);
    assertEquals("2026-11-19", resultat[0]);
    assertNull(resultat[1]);
  }

  @Test
  void parserDatetime_formatInvalide_retourneNull() {
    assertNull(DigitickSource.parserDatetime(null));
    assertNull(DigitickSource.parserDatetime(""));
    assertNull(DigitickSource.parserDatetime("court"));
  }

  @Test
  void normaliserCasse_nomEnMajuscules_retourneNomEnCasseNormale() {
    assertEquals("Ronnie Romero + Gus G", DigitickSource.normaliserCasse("RONNIE ROMERO + GUS G"));
    assertEquals("Anvil + Killer", DigitickSource.normaliserCasse("ANVIL + KILLER"));
    assertEquals("Laura Cox", DigitickSource.normaliserCasse("LAURA COX"));
    assertEquals(
        "Charitable Metal Fest : Ashen + Guests",
        DigitickSource.normaliserCasse("CHARITABLE METAL FEST : ASHEN + GUESTS"));
  }

  @Test
  void normaliserCasse_nomAvecTiret_capitaliseApresLeTiret() {
    assertEquals("Black Star-Riders", DigitickSource.normaliserCasse("BLACK STAR-RIDERS"));
  }

  // --- BilanFetch ---

  @Test
  void preleverBilan_appelReussi_compteUnAppelSansErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("site=lempreinte")))
        .andRespond(withSuccess(HTML_CONCERTS, MediaType.TEXT_HTML));

    DigitickSource source = new DigitickSource(restClient);
    source.fetchSalle("digitick:lempreinte");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_erreurHttp_compteUneErreur() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("site=lempreinte")))
        .andRespond(withServerError());

    DigitickSource source = new DigitickSource(restClient);
    source.fetchSalle("digitick:lempreinte");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(1, bilan.appels());
    assertEquals(1, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_slugVide_neCompteAucunAppel() {
    DigitickSource source = new DigitickSource(RestClient.create());
    source.fetchSalle("digitick:");

    BilanFetch bilan = source.preleverBilan();
    assertEquals(0, bilan.appels());
    assertEquals(0, bilan.erreursHttp());
  }

  @Test
  void preleverBilan_apresPrelevement_remetLesCompteursAZero() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer serveur = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    serveur
        .expect(requestTo(org.hamcrest.Matchers.containsString("site=lempreinte")))
        .andRespond(withServerError());

    DigitickSource source = new DigitickSource(restClient);
    source.fetchSalle("digitick:lempreinte");
    source.preleverBilan();

    BilanFetch second = source.preleverBilan();
    assertEquals(0, second.appels());
    assertEquals(0, second.erreursHttp());
  }
}
