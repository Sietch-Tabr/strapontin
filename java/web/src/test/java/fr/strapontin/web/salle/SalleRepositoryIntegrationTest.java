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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/// Test d'intégration de {@link SalleRepository} avec PostgreSQL réel (Testcontainers).
///
/// <p>Vérifie spécifiquement {@link SalleRepository#lireIdentifiantsSources} : le préfixe
/// Ticketmaster construit à partir de {@code salle_sources.source} doit être abrégé ({@code "tm"})
/// pour correspondre à ce qu'attend {@code TicketmasterSource.accepteIdentifiantSalle} — sans quoi
/// le fetch déclenché à l'ajout d'une salle TM ne fait jamais rien.
///
/// @since 2.7.1
/// @author Kwaite
class SalleRepositoryIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private SalleRepository repository;

  @BeforeAll
  static void migrerBase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM concerts", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    repository = new SalleRepository(jdbc);
  }

  private UUID creerSalle(String salleCle) {
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
    UUID salleId = creerSalle("zenith paris");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'rZASnyZ1eE')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("tm:rZASnyZ1eE", identifiants);
  }

  @Test
  void lireIdentifiantsSources_sourceDigitick_conservePrefixeInchange() {
    UUID salleId = creerSalle("lempreinte");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'digitick', 'lempreinte')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("digitick:lempreinte", identifiants);
  }

  @Test
  void lireIdentifiantsSources_plusieursSources_melangeAbregeEtInchange() {
    UUID salleId = creerSalle("bataclan");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'rZ6SnyZe6d'),"
            + " (CAST(:uuid AS uuid), 'eventim', 'internal:le bataclan')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertTrue(identifiants.contains("tm:rZ6SnyZe6d"));
    assertTrue(identifiants.contains("eventim:internal:le bataclan"));
    assertTrue(
        !identifiants.contains("ticketmaster:"),
        "le préfixe complet ticketmaster: ne doit plus apparaître");
  }

  @Test
  void lireIdentifiantsSources_aucuneSource_retourneNull() {
    UUID salleId = creerSalle("salle sans source");

    assertNull(repository.lireIdentifiantsSources(salleId));
  }

  // upsertSalle/upsertSalleAvecLoc écrivaient nom_norm = salleCle (fonction
  // normaliserSalleCle, sans espaces — ex. "lempreinte"), incompatible avec la tokenisation par
  // espaces qu'attend CorrespondanceSalle.tokensSignificatifs, cassant le matching Wikidata pour
  // toute salle ajoutée via le flux interactif (recherche Nominatim).
  @Test
  void upsertSalle_nomAvecApostropheEtEspaces_nomNormConserveLesEspaces() {
    UUID salleId = repository.upsertSalle("lempreinte", "L'Empreinte");

    String nomNorm =
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = :id", Map.of("id", salleId), String.class);

    assertEquals("l empreinte", nomNorm);
  }

  @Test
  void upsertSalleAvecLoc_nomAvecEspaces_nomNormConserveLesEspaces() {
    UUID salleId =
        repository.upsertSalleAvecLoc("accorhotelsarena", "Accorhotels Arena", "Paris", "75");

    String nomNorm =
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = :id", Map.of("id", salleId), String.class);

    assertEquals("accorhotels arena", nomNorm);
  }

  // mettreAJourSalle mettait à jour nom sans jamais recalculer nom_norm, laissant les
  // deux colonnes désynchronisées après un renommage (ex. correction admin Digitick) — cassant
  // le matching Wikidata/Overpass exactement comme.
  @Test
  void mettreAJourSalle_nouveauNom_recalculeNomNorm() {
    UUID salleId = repository.upsertSalle("elyseemontmartre", "Elysee Montmartre");

    repository.mettreAJourSalle(salleId, "Élysée Montmartre", null, null);

    String nomNorm =
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = :id", Map.of("id", salleId), String.class);

    assertEquals("elysee montmartre", nomNorm);
  }

  @Test
  void mettreAJourSalle_nomNull_conserveNomNormExistant() {
    UUID salleId = repository.upsertSalle("elyseemontmartre", "Élysée Montmartre");

    repository.mettreAJourSalle(salleId, null, "Paris", "75");

    String nomNorm =
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = :id", Map.of("id", salleId), String.class);

    assertEquals("elysee montmartre", nomNorm);
  }

  // rechercherEnBase filtrait sur regexp_replace(salle_norm/nom_norm, '[^a-z0-9]', '', 'g')
  // sans le moindre index depuis la suppression de concerts.salle_cle (V24) — restauré par
  // concerts.salle_cle/salles.nom_cle (colonnes générées indexées, V49). Ces tests valident que
  // la requête retourne toujours les mêmes résultats après le passage aux nouvelles colonnes.

  @Test
  void rechercherEnBase_concertNonResolu_trouveParNomApprochant() {
    jdbc.update(
        "INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source)"
            + " VALUES ('Radiohead', 'radiohead', '2026-06-15', 'Zenith Paris', 'zenith paris',"
            + " 'ticketmaster')",
        Map.of());

    List<Map<String, Object>> resultats = repository.rechercherEnBase("%zenithparis%");

    assertEquals(1, resultats.size());
    assertEquals("Zenith Paris", resultats.get(0).get("salle"));
  }

  @Test
  void rechercherEnBase_concertDejaResolu_neLeRetourneQueViaLeReferentiel() {
    // Un concert déjà résolu (salle_id renseigné) n'est plus jamais trouvé via la branche
    // "concerts non résolus" (salle_id IS NULL) — il ne peut apparaître que via la branche
    // référentiel, une seule fois malgré le concert associé (DISTINCT sur l'union).
    UUID salleId = creerSalle("zenithparis");
    jdbc.update(
        "INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source, salle_id)"
            + " VALUES ('Radiohead', 'radiohead', '2026-06-15', 'Zenith Paris', 'zenith paris',"
            + " 'ticketmaster', CAST(:salleId AS uuid))",
        Map.of("salleId", salleId.toString()));

    List<Map<String, Object>> resultats = repository.rechercherEnBase("%zenithparis%");

    assertEquals(1, resultats.size());
    assertEquals("zenithparis", resultats.get(0).get("salle"));
  }

  @Test
  void rechercherEnBase_salleDigitick_retourneSonSlug() {
    UUID salleId = creerSalle("bataclan");
    jdbc.update(
        "UPDATE salles SET nom = 'Bataclan', nom_norm = 'bataclan' WHERE id = :id",
        Map.of("id", salleId));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'digitick', 'le-bataclan')",
        Map.of("uuid", salleId.toString()));

    List<Map<String, Object>> resultats = repository.rechercherEnBase("%bataclan%");

    assertEquals(1, resultats.size());
    assertEquals("le-bataclan", resultats.get(0).get("digitick_slug"));
  }

  @Test
  void rechercherEnBase_salleReferentielSansConcertNiDigitick_nApparaitPas() {
    creerSalle("salle inconnue du public");

    List<Map<String, Object>> resultats = repository.rechercherEnBase("%salleinconnue%");

    assertTrue(resultats.isEmpty());
  }
}
