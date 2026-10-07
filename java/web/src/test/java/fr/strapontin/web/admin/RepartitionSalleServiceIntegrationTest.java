/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Répartition d'une salle mélangeant plusieurs lieux ({@link RepartitionSalleService}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class RepartitionSalleServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private RepartitionSalleService service;
  private UUID bastille;

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
  void setUp() {
    service =
        new RepartitionSalleService(
            new RepartitionSalleRepository(jdbc), mock(LogAdminService.class));
    bastille = creerSalle("La Comedie Bastille Paris");
  }

  private UUID creerSalle(final String nom) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept) VALUES (:cle, :nom, :cle, '75')"
            + " RETURNING id",
        Map.of("cle", nom.toLowerCase(), "nom", nom),
        UUID.class);
  }

  private UUID evenement(final UUID salle, final String artiste, final String... libelles) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id)"
                + " VALUES (:artiste, CURRENT_DATE + 30, '20:00', 'x', CAST(:salle AS uuid))"
                + " RETURNING id",
            Map.of("artiste", artiste, "salle", salle.toString()),
            UUID.class);
    int i = 0;
    for (final String libelle : libelles) {
      jdbc.update(
          "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
              + " VALUES (CAST(:id AS uuid), CAST('eventim' AS source_concert), :url, :artiste,"
              + " :libelle)",
          new MapSqlParameterSource()
              .addValue("id", id.toString())
              .addValue("url", "https://eventim/" + artiste + "/" + i++)
              .addValue("artiste", artiste)
              .addValue("libelle", libelle));
    }
    return id;
  }

  private UUID salleDe(final UUID evenement) {
    return jdbc.queryForObject(
        "SELECT salle_id FROM evenements WHERE id = CAST(:id AS uuid)",
        Map.of("id", evenement.toString()),
        UUID.class);
  }

  // Trois théâtres distincts mélangés sous un seul salle_id : la Comédie de Paris est extraite
  // vers une salle créée, avec son lieu Eventim.
  @Test
  void repartir_versUneNouvelleSalle_deplaceEvenementsEtLieuEventim() {
    final UUID comedieParis = evenement(bastille, "artiste a", "Comedie de Paris");
    final UUID resteBastille = evenement(bastille, "artiste b", "La Comedie Bastille");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), 'eventim', 'internal:comedie de paris@75009')",
        Map.of("id", bastille.toString()));

    final RepartitionSalleService.ResultatRepartition resultat =
        service.repartir(
            bastille,
            new RequeteRepartitionSalle(
                List.of("Comedie de Paris"), null, null, "Comédie de Paris"));

    assertEquals(1, resultat.evenementsDeplaces());
    assertEquals(1, resultat.lieuxDeplaces());
    assertEquals(resultat.cibleId(), salleDe(comedieParis));
    assertEquals(bastille, salleDe(resteBastille));
  }

  // Séquelle d'une fusion erronée : les concerts « Grande Seine » restés sur le complexe
  // rejoignent la sous-salle existante, un doublon y est fusionné, un événement mixte reste.
  @Test
  void repartir_versUneSalleExistante_fusionneLesDoublonsEtLaisseLesMixtes() {
    final UUID complexe = creerSalle("La Seine Musicale");
    final UUID grandeSeine = creerSalle("La Seine Musicale - Grande Seine");
    evenement(complexe, "lara fabian", "La Seine Musicale - Grande Seine");
    evenement(grandeSeine, "lara fabian", "La Seine Musicale - Grande Seine");
    final UUID mixte =
        evenement(complexe, "spiritbox", "La Seine Musicale - Grande Seine", "La Seine Musicale");

    final RepartitionSalleService.ResultatRepartition resultat =
        service.repartir(
            complexe,
            new RequeteRepartitionSalle(
                List.of("La Seine Musicale - Grande Seine"), null, grandeSeine, null));

    assertEquals(1, resultat.evenementsFusionnes());
    assertEquals(1, resultat.evenementsMixtes());
    assertEquals(complexe, salleDe(mixte));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM evenements WHERE salle_id = CAST(:id AS uuid)",
            Map.of("id", grandeSeine.toString()),
            Integer.class));
  }

  @Test
  void listerLibelles_comptelesEvenementsParLibelle() {
    evenement(bastille, "artiste a", "Comedie de Paris");
    evenement(bastille, "artiste b", "Comedie de Paris");
    evenement(bastille, "artiste c", "La Comedie Bastille");

    assertEquals(
        List.of(
            new RepartitionSalleService.LibelleSource("Comedie de Paris", 2),
            new RepartitionSalleService.LibelleSource("La Comedie Bastille", 1)),
        service.listerLibelles(bastille));
  }

  private void lieu(final UUID salle, final String source, final String id, final String libelle) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, libelle_source)"
            + " VALUES (CAST(:salle AS uuid), :source, :id, :libelle)",
        new MapSqlParameterSource()
            .addValue("salle", salle.toString())
            .addValue("source", source)
            .addValue("id", id)
            .addValue("libelle", libelle));
  }

  private UUID salleDuLieu(final String source, final String id) {
    return jdbc.queryForObject(
        "SELECT salle_id FROM salle_sources WHERE source = :source AND identifiant_externe = :id",
        Map.of("source", source, "id", id),
        UUID.class);
  }

  // Le lieu Ticketmaster resté sur la salle d'origine y ramenait ses concerts à chaque
  // récupération : il suit désormais son libellé comme un lieu Eventim.
  @Test
  void repartir_lieuTicketmasterPortantLeLibelle_estDeplaceAvecLesConcerts() {
    evenement(bastille, "artiste a", "Comedie de Paris");
    lieu(bastille, "ticketmaster", "rZ6SnyZdd7", "Comédie de Paris");
    lieu(bastille, "ticketmaster", "rZbastille", "La Comedie Bastille");

    final RepartitionSalleService.ResultatRepartition resultat =
        service.repartir(
            bastille,
            new RequeteRepartitionSalle(
                List.of("Comedie de Paris"), null, null, "Comédie de Paris"));

    assertEquals(resultat.cibleId(), salleDuLieu("ticketmaster", "rZ6SnyZdd7"));
    assertEquals(bastille, salleDuLieu("ticketmaster", "rZbastille"));
    assertEquals(List.of("ticketmaster:rZbastille"), resultat.lieuxRestes());
  }

  @Test
  void repartir_lieuxChoisisParLAdmin_deplaceExactementCesLieuxEtDeclareLesSallesDistinctes() {
    evenement(bastille, "artiste a", "Comedie de Paris");
    lieu(bastille, "ticketmaster", "rZsansLibelle", null);
    lieu(bastille, "eventim", "internal:comedie de paris", null);

    final RepartitionSalleService.ResultatRepartition resultat =
        service.repartir(
            bastille,
            new RequeteRepartitionSalle(
                List.of("Comedie de Paris"),
                List.of("ticketmaster:rZsansLibelle"),
                null,
                "Comédie de Paris"));

    assertEquals(resultat.cibleId(), salleDuLieu("ticketmaster", "rZsansLibelle"));
    assertEquals(bastille, salleDuLieu("eventim", "internal:comedie de paris"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM salles_paires_distinctes"
                + " WHERE CAST(:a AS uuid) IN (salle_a, salle_b)"
                + " AND CAST(:b AS uuid) IN (salle_a, salle_b)",
            Map.of("a", bastille.toString(), "b", resultat.cibleId().toString()),
            Integer.class));
  }

  @Test
  void repartir_lieuNonRattacheALaSalle_refuseSansRienDeplacer() {
    final UUID autre = creerSalle("Autre Theatre");
    evenement(bastille, "artiste a", "Comedie de Paris");
    lieu(autre, "ticketmaster", "rZautre", null);

    assertThrows(
        ErreurMetier.class,
        () ->
            service.repartir(
                bastille,
                new RequeteRepartitionSalle(
                    List.of("Comedie de Paris"), List.of("ticketmaster:rZautre"), null, "X")));
    assertEquals(autre, salleDuLieu("ticketmaster", "rZautre"));
  }

  @Test
  void listerLieux_toutesSources_nommeChaqueLieu() {
    lieu(bastille, "eventim", "internal:comedie de paris@75009", null);
    lieu(bastille, "ticketmaster", "rZ1", "Comédie Bastille");

    assertEquals(
        List.of(
            new RepartitionSalleService.LieuSource(
                "eventim:internal:comedie de paris@75009", "eventim", "comedie de paris", null),
            new RepartitionSalleService.LieuSource(
                "ticketmaster:rZ1", "ticketmaster", "Comédie Bastille", null)),
        service.listerLieux(bastille));
  }
}
