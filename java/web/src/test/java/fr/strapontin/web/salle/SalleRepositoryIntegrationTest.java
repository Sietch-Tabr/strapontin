/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
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
 * Test d'intégration de {@link SalleRepository} avec PostgreSQL réel (Testcontainers).
 *
 * <p>Vérifie spécifiquement {@link SalleRepository#lireIdentifiantsSources} : le préfixe
 * Ticketmaster construit à partir de {@code salle_sources.source} doit être abrégé ({@code "tm"})
 * pour correspondre à ce qu'attend {@code TicketmasterSource.accepteIdentifiantSalle} — sans quoi
 * le fetch déclenché à l'ajout d'une salle TM ne fait jamais rien.
 *
 * @since 2.7.1
 * @author Kwaite
 */
class SalleRepositoryIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private SalleRepository repository;

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
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    repository = new SalleRepository(jdbc);
  }

  private UUID creerSalle(final String salleCle) {
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:cle, :cle, :cle)",
        Map.of("cle", salleCle));
    return jdbc.queryForObject(
        "SELECT id FROM salles WHERE salle_cle = :cle",
        Map.of("cle", salleCle),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  @Test
  void lireIdentifiantsSources_sourceTicketmaster_abregeLePrefixeEnTm() {
    final UUID salleId = creerSalle("zenith paris");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'rZASnyZ1eE')",
        Map.of("uuid", salleId.toString()));

    final String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("tm:rZASnyZ1eE", identifiants);
  }

  @Test
  void lireIdentifiantsSources_sourceEventim_conservePrefixeInchange() {
    final UUID salleId = creerSalle("lempreinte");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'eventim', 'internal:lempreinte')",
        Map.of("uuid", salleId.toString()));

    final String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("eventim:internal:lempreinte", identifiants);
  }

  @Test
  void lireIdentifiantsSources_plusieursSources_melangeAbregeEtInchange() {
    final UUID salleId = creerSalle("bataclan");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'rZ6SnyZe6d'),"
            + " (CAST(:uuid AS uuid), 'eventim', 'internal:le bataclan')",
        Map.of("uuid", salleId.toString()));

    final String identifiants = repository.lireIdentifiantsSources(salleId);

    assertTrue(identifiants.contains("tm:rZ6SnyZe6d"));
    assertTrue(identifiants.contains("eventim:internal:le bataclan"));
    assertTrue(
        !identifiants.contains("ticketmaster:"),
        "le préfixe complet ticketmaster: ne doit plus apparaître");
  }

  @Test
  void lireIdentifiantsSources_aucuneSource_retourneNull() {
    final UUID salleId = creerSalle("salle sans source");

    assertNull(repository.lireIdentifiantsSources(salleId));
  }

  // upsertSalle écrivait nom_norm = salleCle (fonction
  // normaliserSalleCle, sans espaces — ex. "lempreinte"), incompatible avec la tokenisation par
  // espaces qu'attend CorrespondanceSalle.tokensSignificatifs, cassant le matching Wikidata pour
  // toute salle ajoutée via le flux interactif (recherche Nominatim).
  @Test
  void trouverParIdentifiants_lieuTicketmasterOuElementWikidata_retrouveLaSalle() {
    final UUID olympia = repository.upsertSalle("l olympia paris", "L'Olympia");
    jdbc.update(
        "UPDATE salles SET wikidata_id = 'Q915451' WHERE id = CAST(:id AS uuid)",
        Map.of("id", olympia.toString()));
    repository.enregistrerSalleSource(olympia, "ticketmaster", "KovZpZAEAd6A");

    assertEquals(olympia, repository.trouverParIdentifiants(null, "Q915451"));
    assertEquals(olympia, repository.trouverParIdentifiants("KovZpZAEAd6A", null));
    assertNull(repository.trouverParIdentifiants(null, null));
  }

  @Test
  void upsertSalle_nomAvecApostropheEtEspaces_nomNormConserveLesEspaces() {
    final UUID salleId = repository.upsertSalle("lempreinte", "L'Empreinte");

    final String nomNorm =
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = :id", Map.of("id", salleId), String.class);

    assertEquals("l empreinte", nomNorm);
  }

  // rechercherEnBase filtrait sur regexp_replace(salle_norm/nom_norm, '[^a-z0-9]', '', 'g')
  // sans le moindre index depuis la suppression de concerts.salle_cle (V24) — restauré par
  // concerts.salle_cle/salles.nom_cle (colonnes générées indexées, V49). Ces tests valident que
  // la requête retourne toujours les mêmes résultats après le passage aux nouvelles colonnes.

  private void creerConcert(final String artiste, final String salle, final UUID salleId) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id)"
                + " VALUES (:artisteNorm, '2026-06-15', :salleNorm, CAST(:salleId AS uuid))"
                + " RETURNING id",
            new MapSqlParameterSource()
                .addValue("artisteNorm", artiste.toLowerCase())
                .addValue("salleNorm", salle.toLowerCase())
                .addValue("salleId", salleId != null ? salleId.toString() : null),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', :artiste, :salle)",
        Map.of("id", evenementId.toString(), "artiste", artiste, "salle", salle));
  }

  @Test
  void rechercherEnBase_concertNonResolu_trouveParNomApprochant() {
    creerConcert("Radiohead", "Zenith Paris", null);

    final List<Map<String, Object>> resultats = repository.rechercherEnBase("%zenithparis%");

    assertEquals(1, resultats.size());
    assertEquals("Zenith Paris", resultats.get(0).get("salle"));
  }

  @Test
  void rechercherEnBase_concertDejaResolu_neLeRetourneQueViaLeReferentiel() {
    // Un concert déjà résolu (salle_id renseigné) n'est plus jamais trouvé via la branche
    // "concerts non résolus" (salle_id IS NULL) — il ne peut apparaître que via la branche
    // référentiel, une seule fois malgré le concert associé (DISTINCT sur l'union).
    final UUID salleId = creerSalle("zenithparis");
    creerConcert("Radiohead", "Zenith Paris", salleId);

    final List<Map<String, Object>> resultats = repository.rechercherEnBase("%zenithparis%");

    assertEquals(1, resultats.size());
    assertEquals("zenithparis", resultats.get(0).get("salle"));
  }

  @Test
  void rechercherEnBase_salleReferentielSansConcert_nApparaitPas() {
    creerSalle("salle inconnue du public");

    final List<Map<String, Object>> resultats = repository.rechercherEnBase("%salleinconnue%");

    assertTrue(resultats.isEmpty());
  }

  @Test
  void listerPourUtilisateur_salleSansAucuneSource_neEstPasCouverte() {
    jdbc.update("DELETE FROM user_salles", Map.of());
    jdbc.update("DELETE FROM users WHERE id = 'u-couv'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u-couv', 'ucouv', 'c@t.fr')", Map.of());
    final UUID couverte = creerSalle("zenith couverte");
    final UUID nonCouverte = creerSalle("salle locale");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', 'tm-couv')",
        Map.of("id", couverte.toString()));
    for (final UUID salle : List.of(couverte, nonCouverte)) {
      jdbc.update(
          "INSERT INTO user_salles (user_id, salle_id) VALUES ('u-couv', CAST(:id AS uuid))",
          Map.of("id", salle.toString()));
    }

    final List<SalleUtilisateur> suivies = repository.listerPourUtilisateur("u-couv");

    assertEquals(2, suivies.size());
    assertTrue(suivies.stream().filter(s -> s.id().equals(couverte)).findFirst().get().couverte());
    assertEquals(
        false,
        suivies.stream().filter(s -> s.id().equals(nonCouverte)).findFirst().get().couverte());
  }
}
