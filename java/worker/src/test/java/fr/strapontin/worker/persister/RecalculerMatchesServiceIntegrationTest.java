/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Tests d'intégration de [RecalculerMatchesService] avec PostgreSQL réel (Testcontainers).
///
/// @since 2.0.1
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class RecalculerMatchesServiceIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean
  @SuppressWarnings("unused")
  TicketmasterSource ticketmasterSource;

  @Autowired private RecalculerMatchesService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  private static final String DATE_FUTURE = LocalDate.now().plusMonths(3).toString();
  private static final String DATE_PASSEE = LocalDate.now().minusDays(1).toString();

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM users", Map.of());
    jdbc.update("DELETE FROM concerts", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
  }

  private void insererUser(String id) {
    jdbc.update(
        "INSERT INTO users (id, name, email) VALUES (:id, :name, :email)",
        Map.of("id", id, "name", "User " + id, "email", id + "@test.fr"));
  }

  private void insererArtiste(String userId, String norm, String affiche) {
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche)"
            + " VALUES (:userId, :norm, :affiche)",
        Map.of("userId", userId, "norm", norm, "affiche", affiche));
  }

  private void insererSalle(String salleId) {
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm) VALUES (:id, :nom, :norm)",
        Map.of("id", salleId, "nom", salleId, "norm", salleId));
  }

  private void insererUserSalle(String userId, String salleId) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES (:userId, :salleId)",
        Map.of("userId", userId, "salleId", salleId));
  }

  private void insererUserSalleAvecUuid(String userId, String salleId, UUID salleUuid) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id, salle_uuid)"
            + " VALUES (:userId, :salleId, CAST(:salleUuid AS uuid))",
        Map.of("userId", userId, "salleId", salleId, "salleUuid", salleUuid.toString()));
  }

  private void insererConcertAvecSalleUuid(
      String artiste, String artisteNorm, String date, String salleNorm, UUID salleUuid) {
    jdbc.update(
        """
        INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source, salle_uuid)
        VALUES (:artiste, :norm, :date::date, :salle, :salleNorm,
                CAST('ticketmaster' AS source_concert), CAST(:salleUuid AS uuid))
        """,
        Map.of(
            "artiste", artiste,
            "norm", artisteNorm,
            "date", date,
            "salle", salleNorm,
            "salleNorm", salleNorm,
            "salleUuid", salleUuid.toString()));
  }

  private void insererConcert(String artiste, String artisteNorm, String date, String salleNorm) {
    jdbc.update(
        """
        INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source)
        VALUES (:artiste, :norm, :date::date, :salle, :salleNorm,
                CAST('ticketmaster' AS source_concert))
        """,
        Map.of(
            "artiste", artiste,
            "norm", artisteNorm,
            "date", date,
            "salle", salleNorm,
            "salleNorm", salleNorm));
  }

  private long compterMatches(String userId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_matches WHERE user_id = :userId",
            Map.of("userId", userId),
            Long.class);
    return count != null ? count : 0L;
  }

  @Test
  void recalculerMatches_avecArtiste_insereMatch() {
    insererUser("u1");
    insererArtiste("u1", "julien clerc", "Julien Clerc");
    insererConcert("Julien Clerc", "julien clerc", DATE_FUTURE, "olympia");

    service.recalculerMatches(null);

    assertEquals(1, compterMatches("u1"), "Un match doit être inséré pour l'artiste suivi");
  }

  @Test
  void recalculerMatches_avecSalle_insereMatch() {
    insererUser("u2");
    insererSalle("olympia");
    insererUserSalle("u2", "olympia");
    insererConcert("Artiste Quelconque", "artiste quelconque", DATE_FUTURE, "olympia");

    service.recalculerMatches(null);

    assertEquals(1, compterMatches("u2"), "Un match doit être inséré pour la salle suivie");
  }

  @Test
  void recalculerMatches_sansCorrespondance_pasDeMatch() {
    insererUser("u3");
    insererArtiste("u3", "julien clerc", "Julien Clerc");
    insererConcert("Autre Artiste", "autre artiste", DATE_FUTURE, "zenith");

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u3"), "Aucun match attendu si l'artiste ne correspond pas");
  }

  @Test
  void recalculerMatches_concertPasse_pasDeMatch() {
    insererUser("u4");
    insererArtiste("u4", "stromae", "Stromae");
    insererConcert("Stromae", "stromae", DATE_PASSEE, "stade");

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u4"), "Un concert passé ne doit pas générer de match");
  }

  @Test
  void recalculerMatches_filtreParUserIds_recalculeUniquementUsersSpecifies() {
    insererUser("u5");
    insererUser("u6");
    insererArtiste("u5", "aya nakamura", "Aya Nakamura");
    insererArtiste("u6", "aya nakamura", "Aya Nakamura");
    insererConcert("Aya Nakamura", "aya nakamura", DATE_FUTURE, "accor");

    service.recalculerMatches(List.of("u5"));

    assertEquals(1, compterMatches("u5"), "u5 doit avoir un match");
    assertEquals(0, compterMatches("u6"), "u6 ne doit pas être recalculé");
  }

  @Test
  void recalculerMatches_matchDevenuInvalide_supprime() {
    insererUser("u7");
    insererArtiste("u7", "indochine", "Indochine");
    insererConcert("Indochine", "indochine", DATE_FUTURE, "arena");

    service.recalculerMatches(null);
    assertEquals(1, compterMatches("u7"));

    jdbc.update("DELETE FROM user_artistes WHERE user_id = :userId", Map.of("userId", "u7"));

    service.recalculerMatches(List.of("u7"));
    assertEquals(
        0, compterMatches("u7"), "Le match doit être supprimé si l'artiste n'est plus suivi");
  }

  @Test
  void recalculerMatches_utilisateursMultiples_tousRecalcules() {
    insererUser("u10");
    insererUser("u11");
    insererUser("u12");
    insererArtiste("u10", "daft punk", "Daft Punk");
    insererArtiste("u11", "justice", "Justice");
    insererArtiste("u12", "moderat", "Moderat");
    insererConcert("Daft Punk", "daft punk", DATE_FUTURE, "bercy");
    insererConcert("Justice", "justice", DATE_FUTURE, "rex");
    insererConcert("Moderat", "moderat", DATE_FUTURE, "trabendo");

    int traites = service.recalculerMatches(null);

    assertTrue(traites >= 3, "Au moins 3 utilisateurs doivent être traités");
    assertEquals(1, compterMatches("u10"), "u10 doit avoir son match");
    assertEquals(1, compterMatches("u11"), "u11 doit avoir son match");
    assertEquals(1, compterMatches("u12"), "u12 doit avoir son match");
  }

  private UUID obtenirSalleUuid(String salleId) {
    return jdbc.queryForObject(
        "SELECT salle_uuid FROM salles WHERE id = :id",
        Map.of("id", salleId),
        (rs, _) -> rs.getObject("salle_uuid", UUID.class));
  }

  @Test
  void recalculerMatches_salleAvecUuid_matcheViaSalleUuid() {
    // ticket #621 — correspondance via salle_uuid même sans salle_cle dans concerts
    insererUser("u20");
    insererSalle("plenitude arena");
    UUID salleUuid = obtenirSalleUuid("plenitude arena");
    insererUserSalleAvecUuid("u20", "plenitude arena", salleUuid);
    insererConcertAvecSalleUuid(
        "Coldplay", "coldplay", DATE_FUTURE, "plenitude arena renomme", salleUuid);

    service.recalculerMatches(List.of("u20"));

    assertEquals(
        1, compterMatches("u20"), "match attendu via salle_uuid même si salle_cle différent");
  }

  @Test
  void recalculerMatches_salleRenommee_matcheViaUuidApresRenommage() {
    // Scénario Plenitude Arena : avant = "paris la defense arena", après = "plenitude arena"
    insererUser("u21");
    insererSalle("paris la defense arena");
    UUID salleUuid = obtenirSalleUuid("paris la defense arena");
    insererUserSalleAvecUuid("u21", "paris la defense arena", salleUuid);
    // Concert créé après renommage : salle_norm différente de celle inscrite par l'user
    insererConcertAvecSalleUuid("Dua Lipa", "dua lipa", DATE_FUTURE, "plenitude arena", salleUuid);

    service.recalculerMatches(List.of("u21"));

    assertEquals(1, compterMatches("u21"), "match attendu via salle_uuid après renommage de salle");
  }

  @Test
  void recalculerMatches_userSansAbonnement_matchesOrphelinsPurges() {
    insererUser("u8");
    insererConcert("Test", "test", DATE_FUTURE, "test");

    String concertId =
        jdbc.queryForObject("SELECT id::text FROM concerts LIMIT 1", Map.of(), String.class);
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id) VALUES (:userId, :concertId::uuid)",
        Map.of("userId", "u8", "concertId", concertId));

    assertEquals(1, compterMatches("u8"));

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u8"), "Les matches orphelins doivent être purgés");
  }
}
