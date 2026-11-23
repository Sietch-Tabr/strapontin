/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.concert.FiltreConcerts;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/**
 * Recherche texte de Découverte sur base réelle : l'artiste et la salle recherchés sont ceux de la
 * meilleure observation de la fiche, le préfiltre sur toutes les observations n'y change rien.
 *
 * @since 3.2.1
 * @author Kwaite
 */
class DecouverteRechercheIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;
  private DecouverteService service;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void creerService() {
    service = new DecouverteService(jdbc, mock(RestClient.class));
  }

  private UUID evenement(final String artisteNorm) {
    return jdbc.queryForObject(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, dept)"
            + " VALUES (:artiste, :date, 'x', '75') RETURNING id",
        new MapSqlParameterSource()
            .addValue("artiste", artisteNorm)
            .addValue("date", LocalDate.now().plusDays(10)),
        UUID.class);
  }

  private void observation(
      final UUID evenement, final String source, final String artiste, final String salle) {
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (:id, CAST(:source AS source_concert), :url, :artiste, :salle)",
        new MapSqlParameterSource()
            .addValue("id", evenement)
            .addValue("source", source)
            .addValue("url", "https://billetterie.test/" + evenement + source)
            .addValue("artiste", artiste)
            .addValue("salle", salle));
  }

  private List<String> rechercher(final String texte) {
    final FiltreConcerts filtre =
        new FiltreConcerts(
            texte, null, null, null, null, null, null, null, null, null, List.of("75"));
    return service.decouvrir("utilisateur-sans-config", filtre).concerts().stream()
        .map(ConcertDecouverte::artiste)
        .sorted()
        .toList();
  }

  @Test
  void decouvrir_rechercheSurLArtisteOuLaSalleAffiches_retrouveLesFichesCorrespondantes() {
    observation(evenement("alpha"), "ticketmaster", "Alpha Quartet", "Zenith de Paris");
    observation(evenement("beta"), "ticketmaster", "Zenith Orchestra", "Olympia");
    observation(evenement("gamma"), "ticketmaster", "Gamma", "Bercy");

    assertThat(rechercher("zenith")).containsExactly("Alpha Quartet", "Zenith Orchestra");
    assertThat(rechercher("olympia")).containsExactly("Zenith Orchestra");
    assertThat(rechercher("GAMMA")).containsExactly("Gamma");
    assertThat(rechercher("absent")).isEmpty();
  }

  // Une observation secondaire qui correspond ne suffit pas : la fiche affiche celle de la
  // billetterie prioritaire, la recherche porte sur ce qui est affiché.
  @Test
  void decouvrir_observationSecondaireSeule_neRetientPasLaFiche() {
    final UUID fiche = evenement("delta");
    observation(fiche, "ticketmaster", "Delta", "Bercy");
    observation(fiche, "eventim", "Delta", "Salle Zenith Bercy");

    assertThat(rechercher("zenith")).isEmpty();
    assertThat(rechercher("bercy")).containsExactly("Delta");
  }
}
