/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Détail du statut « À vérifier » / « Invalide » du référentiel : source concernée, nombre de
 * fetchs vides et date, sur base réelle.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class ReferentielStatutDetailIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private ReferentielSallesService salles;
  private ReferentielArtistesService artistes;

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
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
    final LogAdminService journal = new LogAdminService(jdbc, new ObjectMapper());
    salles = new ReferentielSallesService(jdbc, journal);
    artistes = new ReferentielArtistesService(jdbc, journal);
  }

  private UUID creerSalle(final String nom) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:n, :n, :n) RETURNING id",
        Map.of("n", nom),
        UUID.class);
  }

  private void ajouterSource(final UUID salle, final String source, final String depuis) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, nb_zero_consecutifs,"
            + " suspect_depuis) VALUES (CAST(:id AS uuid), :source, :ident, :nb, "
            + (depuis == null ? "NULL" : "now() - interval '" + depuis + "'")
            + ")",
        Map.of(
            "id",
            salle.toString(),
            "source",
            source,
            "ident",
            source + "-" + UUID.randomUUID(),
            "nb",
            depuis == null ? 0 : 4));
  }

  private SalleReferentiel ligne(final String nom) {
    return salles
        .lister(
            new ReferentielSallesService.FiltresRechercheSalles(0, nom, null, null, null, false))
        .salles()
        .getFirst();
  }

  @Test
  void lister_salleAVerifier_nommeLaSourceSuspecteSonCompteurEtSaDate() {
    final UUID salle = creerSalle("zenith test");
    ajouterSource(salle, "ticketmaster", "2 hours");
    ajouterSource(salle, "eventim", null);

    final SalleReferentiel ligne = ligne("zenith test");

    assertEquals("a_verifier", ligne.statut());
    assertEquals(1, ligne.suspicions().size());
    assertEquals("ticketmaster", ligne.suspicions().getFirst().source());
    assertEquals(4, ligne.suspicions().getFirst().nbVides());
    assertNotNull(ligne.suspicions().getFirst().depuis());
  }

  @Test
  void lister_salleInvalide_decritToutesLesSourcesSuspectesDuPlusAncienAuPlusRecent() {
    final UUID salle = creerSalle("olympia test");
    ajouterSource(salle, "eventim", "3 days");
    ajouterSource(salle, "ticketmaster", "2 days");

    final SalleReferentiel ligne = ligne("olympia test");

    assertEquals("invalide", ligne.statut());
    assertEquals("eventim", ligne.suspicions().get(0).source());
    assertEquals("ticketmaster", ligne.suspicions().get(1).source());
  }

  @Test
  void lister_salleAJour_neDecritAucuneSuspicion() {
    final UUID salle = creerSalle("bataclan test");
    ajouterSource(salle, "ticketmaster", null);

    final SalleReferentiel ligne = ligne("bataclan test");

    assertEquals("a_jour", ligne.statut());
    assertTrue(ligne.suspicions().isEmpty());
  }

  @Test
  void listerArtistes_artisteInvalide_donneLaDateDuDoute() {
    final UUID artiste =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('Jain test', 'jain test') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe,"
            + " necessite_intervention_le) VALUES (CAST(:id AS uuid), 'ticketmaster', 'tm1',"
            + " now())",
        Map.of("id", artiste.toString()));
    final UUID sain =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('Sain test', 'sain test') RETURNING id",
            Map.of(),
            UUID.class);

    final ReponseReferentielArtistes reponse = artistes.lister(0, "test", null, false);

    final ArtisteReferentiel invalide =
        reponse.artistes().stream().filter(a -> a.id().equals(artiste)).findFirst().orElseThrow();
    final ArtisteReferentiel ok =
        reponse.artistes().stream().filter(a -> a.id().equals(sain)).findFirst().orElseThrow();
    assertEquals("invalide", invalide.statut());
    assertNotNull(invalide.interventionDepuis());
    assertNull(ok.interventionDepuis());
  }

  @Test
  void lister_lieuEventim_estExposeParSalle() {
    final UUID salle = creerSalle("accor test");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, libelle_source)"
            + " VALUES (CAST(:id AS uuid), 'eventim', 'internal:accor arena', 'Accor Arena')",
        Map.of("id", salle.toString()));

    assertEquals("Accor Arena", ligne("accor test").lieuEventim());
  }

  @Test
  void lister_sansSource_neRemonteQueLesSallesSansAucuneSource() {
    creerSalle("sans source test");
    final UUID avecSource = creerSalle("avec source test");
    ajouterSource(avecSource, "ticketmaster", null);

    final var reponse =
        salles.lister(
            new ReferentielSallesService.FiltresRechercheSalles(
                0, "source test", null, null, null, false, java.util.List.of(), true));

    assertEquals(1, reponse.salles().size());
    assertEquals("sans source test", reponse.salles().getFirst().nom());
  }
}
