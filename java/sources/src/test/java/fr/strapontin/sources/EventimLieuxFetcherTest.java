/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.assertj.core.api.Assertions.assertThat;

import fr.strapontin.core.ConcertBrut;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EventimLieuxFetcherTest {

  private static final int TAILLE_PAGE = 50;
  private static final int MAX_PAGES = 10;

  private final List<URI> requetes = new ArrayList<>();

  private static ConcertBrut concert(final LocalDate date, final String dept) {
    return new ConcertBrut(
        "Artiste",
        date.toString(),
        "20:00",
        "Salle",
        "Ville",
        dept,
        "https://eventim.fr/" + date,
        "eventim",
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static List<ConcertBrut> page(final LocalDate date, final String dept) {
    return IntStream.range(0, TAILLE_PAGE).mapToObj(_ -> concert(date, dept)).toList();
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
        fetcher(_ -> new PageProduitsEventim(List.of(concert(demain, "77")), 1, true))
            .fetchLieu("LEMPREINTE SAVIGNY LE TEMPLE CEDEX");

    assertThat(concerts).hasSize(1);
    assertThat(requetes).hasSize(1);
    assertThat(requetes.getFirst().getQuery())
        .contains("venue_names=LEMPREINTE SAVIGNY LE TEMPLE CEDEX")
        .doesNotContain("in_stock");
  }

  @Test
  void fetchVilles_premiereFenetrePlafonnee_repriseAuDernierJourRecu() {
    final LocalDate derniereDate = LocalDate.now().plusDays(20);
    final int totalAnnonce = TAILLE_PAGE * MAX_PAGES + 30;

    final ResultatRegion resultat =
        fetcher(
                uri -> {
                  if (datesDebut().size() == 1) {
                    return new PageProduitsEventim(page(derniereDate, "69"), totalAnnonce, true);
                  }
                  return new PageProduitsEventim(List.of(concert(derniereDate, "69")), 1, true);
                })
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(datesDebut()).containsExactly(LocalDate.now().toString(), derniereDate.toString());
    assertThat(resultat.concerts()).hasSize(TAILLE_PAGE * MAX_PAGES + 1);
    assertThat(resultat.horizon()).isEqualTo(LocalDate.now().plusMonths(6).minusDays(1));
  }

  @Test
  void fetchVilles_plafondSansProgressionDeDate_horizonVeilleDuDernierJour() {
    final LocalDate aujourdHui = LocalDate.now();

    final ResultatRegion resultat =
        fetcher(_ -> new PageProduitsEventim(page(aujourdHui, "69"), 10_000, true))
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
                        : new PageProduitsEventim(page(demain, "69"), 80, true))
            .fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.horizon()).isNull();
    assertThat(resultat.concerts()).hasSize(TAILLE_PAGE);
  }

  @Test
  void fetchVilles_villeHomonymeAutreDepartement_concertEcarte() {
    final LocalDate demain = LocalDate.now().plusDays(1);

    final ResultatRegion resultat =
        fetcher(
                _ ->
                    new PageProduitsEventim(
                        List.of(concert(demain, "69"), concert(demain, "33")), 2, true))
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
        .noneMatch(q -> q.contains("city_names"));
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
            _ -> new PageProduitsEventim(page(demain, "69"), 80, true),
            () -> false);

    final ResultatRegion resultat =
        fetcher.fetchVilles(List.of("Lyon"), Set.of("69"), List.of("Concerts"));

    assertThat(resultat.horizon()).isNull();
  }

  @Test
  void compterConcerts_succes_retourneLeTotalAnnonce() {
    final int total =
        fetcher(_ -> new PageProduitsEventim(List.of(), 42, true)).compterConcerts("Accor Arena");

    assertThat(total).isEqualTo(42);
    assertThat(requetes.getFirst().getQuery()).contains("top=1").doesNotContain("top=50");
  }

  @Test
  void compterConcerts_echecHttp_retourneMoinsUn() {
    assertThat(fetcher(_ -> PageProduitsEventim.ECHEC).compterConcerts("Accor Arena"))
        .isEqualTo(-1);
  }
}
