/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.ReponseIncompleteException;
import fr.strapontin.sources.ResultatRegion;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EventimLieuxFetcherTest {

  private static final int TAILLE_PAGE = 20;
  private static final int PLAFOND = EventimSource.PLAFOND_PAGES;

  private final List<URI> requetes = new ArrayList<>();

  private static ConcertBrut concert(final LocalDate date, final String dept) {
    return ConcertBrut.nouveau()
        .artiste("Artiste")
        .date(date.toString())
        .heure("20:00")
        .salle("Salle")
        .ville("Ville")
        .dept(dept)
        .url("https://eventim.fr/" + date)
        .source("eventim")
        .construire();
  }

  private static List<ConcertBrut> page(final LocalDate date, final String dept) {
    return IntStream.range(0, TAILLE_PAGE).mapToObj(_ -> concert(date, dept)).toList();
  }

  private static PageProduitsEventim reponse(
      final List<ConcertBrut> concerts, final int total, final int totalPages) {
    return new PageProduitsEventim(concerts, total, totalPages, concerts.size(), true);
  }

  private EventimLieuxFetcher fetcher(final Function<URI, PageProduitsEventim> chargeur) {
    return new EventimLieuxFetcher(
        "https://api.eventim.test/products",
        "web__eventim-fr",
        uri -> {
          requetes.add(uri);
          return chargeur.apply(uri);
        },
        () -> true);
  }

  private List<String> datesDebut() {
    return requetes.stream()
        .map(URI::getQuery)
        .filter(q -> q.contains("page=1&") || q.endsWith("page=1"))
        .map(q -> q.replaceAll(".*date_from=([0-9-]+).*", "$1"))
        .toList();
  }

  @Test
  void fetchLieu_resultatSousLePlafond_uneSeuleFenetreFiltreeParLieu() {
    final LocalDate demain = LocalDate.now().plusDays(1);

    final List<ConcertBrut> concerts =
        fetcher(_ -> reponse(List.of(concert(demain, "77")), 1, 1))
            .fetchLieu("LEMPREINTE SAVIGNY LE TEMPLE CEDEX");

    assertThat(concerts).hasSize(1);
    assertThat(requetes).hasSize(1);
    assertThat(requetes.getFirst().getQuery())
        .contains("venue_names=LEMPREINTE SAVIGNY LE TEMPLE CEDEX")
        .doesNotContain("in_stock");
  }

  // Lieu en échec : ses concerts non reçus ne sont pas absents (ancien comportement : couverture
  // complète supposée, une absence par concert du lieu).
  @Test
  void fetchLieu_echecHttp_leveReponseIncompleteAvecLesConcertsRecus() {
    final LocalDate demain = LocalDate.now().plusDays(1);
    final EventimLieuxFetcher lieux =
        fetcher(
            uri ->
                uri.getQuery().contains("page=2")
                    ? PageProduitsEventim.ECHEC
                    : reponse(page(demain, "77"), 80, 4));

    final ReponseIncompleteException incomplete =
        assertThrows(ReponseIncompleteException.class, () -> lieux.fetchLieu("LE PLAN"));

    assertThat(incomplete.concertsRecus()).hasSize(TAILLE_PAGE);
    assertThat(incomplete.couvertJusquau()).isNull();
  }

  @Test
  void fetchVilles_premiereFenetrePlafonnee_repriseAuDernierJourRecu() {
    final LocalDate derniereDate = LocalDate.now().plusDays(20);
    final int totalAnnonce = 10_000;

    final ResultatRegion resultat =
        fetcher(
                uri -> {
                  if (datesDebut().size() == 1) {
                    return reponse(page(derniereDate, "69"), totalAnnonce, PLAFOND);
                  }
                  return reponse(List.of(concert(derniereDate, "69")), 1, 1);
                })
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(datesDebut()).containsExactly(LocalDate.now().toString(), derniereDate.toString());
    assertThat(resultat.concerts()).hasSize(TAILLE_PAGE * PLAFOND + 1);
    assertThat(resultat.horizon()).isEqualTo(LocalDate.now().plusMonths(6).minusDays(1));
  }

  @Test
  void fetchVilles_totalPagesSousLePlafond_litChaquePageSansTopEtCouvreTout() {
    final LocalDate demain = LocalDate.now().plusDays(1);

    final ResultatRegion resultat =
        fetcher(_ -> reponse(page(demain, "69"), 2267, 3))
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(requetes).hasSize(3);
    assertThat(requetes).extracting(URI::getQuery).noneMatch(q -> q.contains("top="));
    assertThat(requetes.get(2).getQuery()).contains("page=3");
    assertThat(resultat.concerts()).hasSize(TAILLE_PAGE * 3);
    assertThat(resultat.horizon()).isEqualTo(LocalDate.now().plusMonths(6).minusDays(1));
  }

  @Test
  void fetchVilles_plafondSansProgressionDeDate_horizonVeilleDuDernierJour() {
    final LocalDate aujourdHui = LocalDate.now();

    final ResultatRegion resultat =
        fetcher(_ -> reponse(page(aujourdHui, "69"), 10_000, PLAFOND))
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.horizon()).isEqualTo(aujourdHui.minusDays(1));
  }

  @Test
  void fetchVilles_echecHttp_horizonNullConcertsDejaRecusConserves() {
    final LocalDate demain = LocalDate.now().plusDays(1);

    final ResultatRegion resultat =
        fetcher(
                uri ->
                    uri.getQuery().contains("page=2")
                        ? PageProduitsEventim.ECHEC
                        : reponse(page(demain, "69"), 80, 4))
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.horizon()).isNull();
    assertThat(resultat.concerts()).hasSize(TAILLE_PAGE);
  }

  @Test
  void fetchVilles_villeHomonymeAutreDepartement_concertEcarte() {
    final LocalDate demain = LocalDate.now().plusDays(1);

    final ResultatRegion resultat =
        fetcher(_ -> reponse(List.of(concert(demain, "69"), concert(demain, "33")), 2, 1))
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.concerts()).extracting(ConcertBrut::dept).containsExactly("69");
  }

  @Test
  void fetchVilles_plusDeDixVilles_interrogeesParPaquetsDeDix() {
    final List<String> villes = IntStream.range(0, 11).mapToObj(i -> "Ville" + i).toList();

    fetcher(_ -> PageProduitsEventim.VIDE).fetchVilles(villes, Set.of("69"), List.of("Concerts"));

    assertThat(requetes).hasSize(2);
    assertThat(requetes.get(0).getQuery()).contains("city_names=Ville9").doesNotContain("Ville10");
    assertThat(requetes.get(1).getQuery()).contains("city_names=Ville10").doesNotContain("Ville9");
  }

  @Test
  void fetchVilles_departementParis_interrogeAussiChaqueCodePostal() {
    fetcher(_ -> PageProduitsEventim.VIDE)
        .fetchVilles(List.of("Paris"), Set.of("75"), List.of("Concerts"));

    assertThat(requetes).hasSize(22);
    assertThat(requetes.stream().map(URI::getQuery).filter(q -> q.contains("postal_code=")))
        .hasSize(21)
        .anyMatch(q -> q.contains("postal_code=75001"))
        .anyMatch(q -> q.contains("postal_code=75116"))
        .allMatch(q -> q.contains("top=50") && !q.contains("page="))
        .noneMatch(q -> q.contains("city_names"));
  }

  @Test
  void fetchVilles_codePostalReponsePleine_repriseAuDernierJourSansPage() {
    final LocalDate derniereDate = LocalDate.now().plusDays(12);
    final List<ConcertBrut> pleine =
        IntStream.range(0, EventimSource.TOP_MAX)
            .mapToObj(_ -> concert(derniereDate, "75"))
            .toList();

    fetcher(
            uri ->
                uri.getQuery().contains("postal_code=75001")
                        && uri.getQuery().contains("date_from=" + LocalDate.now())
                    ? reponse(pleine, 3120, 63)
                    : PageProduitsEventim.VIDE)
        .fetchVilles(List.of(), Set.of("75"), List.of("Concerts"));

    assertThat(requetes)
        .extracting(URI::getQuery)
        .anyMatch(q -> q.contains("postal_code=75001") && q.contains("date_from=" + derniereDate));
  }

  @Test
  void fetchVilles_departementHorsParis_aucunCodePostal() {
    fetcher(_ -> PageProduitsEventim.VIDE)
        .fetchVilles(List.of("Bordeaux"), Set.of("33"), List.of("Concerts"));

    assertThat(requetes).hasSize(1);
    assertThat(requetes.getFirst().getQuery()).doesNotContain("postal_code");
  }

  @Test
  void fetchVilles_attenteInterrompue_horizonNull() {
    final LocalDate demain = LocalDate.now().plusDays(1);
    final EventimLieuxFetcher fetcher =
        new EventimLieuxFetcher(
            "https://api.eventim.test/products",
            "web__eventim-fr",
            _ -> reponse(page(demain, "69"), 80, 4),
            () -> false);

    final ResultatRegion resultat =
        fetcher.fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.horizon()).isNull();
  }

  @Test
  void compterConcerts_succes_retourneLeTotalAnnonce() {
    final int total = fetcher(_ -> reponse(List.of(), 42, 3)).compterConcerts("Accor Arena");

    assertThat(total).isEqualTo(42);
    assertThat(requetes.getFirst().getQuery()).contains("top=1").doesNotContain("top=50");
  }

  @Test
  void compterConcerts_echecHttp_retourneMoinsUn() {
    assertThat(fetcher(_ -> PageProduitsEventim.ECHEC).compterConcerts("Accor Arena"))
        .isEqualTo(-1);
  }
}
