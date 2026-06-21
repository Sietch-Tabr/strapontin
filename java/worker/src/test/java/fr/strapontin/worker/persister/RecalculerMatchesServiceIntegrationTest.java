/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
import java.time.LocalDate;
import java.util.HashMap;
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
    jdbc.update("DELETE FROM artistes", Map.of());
    jdbc.update("DELETE FROM concerts", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
  }

  private void insererUser(String id) {
    jdbc.update(
        "INSERT INTO users (id, name, email) VALUES (:id, :name, :email)",
        Map.of("id", id, "name", "User " + id, "email", id + "@test.fr"));
  }

  private UUID insererArtiste(String userId, String norm, String affiche) {
    UUID artisteId =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES (:nom, :norm)"
                + " ON CONFLICT (nom_norm) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
            Map.of("nom", affiche, "norm", norm),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_affiche, artiste_id)"
            + " VALUES (:userId, :affiche, CAST(:artisteId AS uuid))",
        Map.of("userId", userId, "affiche", affiche, "artisteId", artisteId.toString()));
    return artisteId;
  }

  private UUID insererSalle(String salleCle) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:id, :nom, :norm)"
            + " ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
        Map.of("id", salleCle, "nom", salleCle, "norm", salleCle),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  private void insererUserSalle(String userId, UUID salleUuid) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_uuid)"
            + " VALUES (:userId, CAST(:salleUuid AS uuid))",
        Map.of("userId", userId, "salleUuid", salleUuid.toString()));
  }

  private void insererConcert(
      String artiste, String norm, String date, String salleNorm, UUID artisteId, UUID salleUuid) {
    Map<String, Object> params = new HashMap<>();
    params.put("artiste", artiste);
    params.put("norm", norm);
    params.put("date", date);
    params.put("salle", salleNorm);
    params.put("salleNorm", salleNorm);
    params.put("artisteId", artisteId != null ? artisteId.toString() : null);
    params.put("salleUuid", salleUuid != null ? salleUuid.toString() : null);
    jdbc.update(
        """
        INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source, artiste_id, salle_uuid)
        VALUES (:artiste, :norm, :date::date, :salle, :salleNorm,
                CAST('ticketmaster' AS source_concert), CAST(:artisteId AS uuid), CAST(:salleUuid AS uuid))
        """,
        params);
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
    UUID aId = insererArtiste("u1", "julien clerc", "Julien Clerc");
    insererConcert("Julien Clerc", "julien clerc", DATE_FUTURE, "olympia", aId, null);

    service.recalculerMatches(null);

    assertEquals(1, compterMatches("u1"), "Un match doit être inséré pour l'artiste suivi");
  }

  @Test
  void recalculerMatches_avecSalle_insereMatch() {
    insererUser("u2");
    UUID sId = insererSalle("olympia");
    insererUserSalle("u2", sId);
    insererConcert("Artiste Quelconque", "artiste quelconque", DATE_FUTURE, "olympia", null, sId);

    service.recalculerMatches(null);

    assertEquals(1, compterMatches("u2"), "Un match doit être inséré pour la salle suivie");
  }

  @Test
  void recalculerMatches_sansCorrespondance_pasDeMatch() {
    insererUser("u3");
    insererArtiste("u3", "julien clerc", "Julien Clerc");
    insererConcert("Autre Artiste", "autre artiste", DATE_FUTURE, "zenith", null, null);

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u3"), "Aucun match attendu si l'artiste ne correspond pas");
  }

  @Test
  void recalculerMatches_concertPasse_pasDeMatch() {
    insererUser("u4");
    UUID aId = insererArtiste("u4", "stromae", "Stromae");
    insererConcert("Stromae", "stromae", DATE_PASSEE, "stade", aId, null);

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u4"), "Un concert passé ne doit pas générer de match");
  }

  @Test
  void recalculerMatches_filtreParUserIds_recalculeUniquementUsersSpecifies() {
    insererUser("u5");
    insererUser("u6");
    UUID aId5 = insererArtiste("u5", "aya nakamura", "Aya Nakamura");
    insererArtiste("u6", "aya nakamura", "Aya Nakamura");
    insererConcert("Aya Nakamura", "aya nakamura", DATE_FUTURE, "accor", aId5, null);

    service.recalculerMatches(List.of("u5"));

    assertEquals(1, compterMatches("u5"), "u5 doit avoir un match");
    assertEquals(0, compterMatches("u6"), "u6 ne doit pas être recalculé");
  }

  @Test
  void recalculerMatches_matchDevenuInvalide_supprime() {
    insererUser("u7");
    UUID aId = insererArtiste("u7", "indochine", "Indochine");
    insererConcert("Indochine", "indochine", DATE_FUTURE, "arena", aId, null);

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
    UUID aId10 = insererArtiste("u10", "daft punk", "Daft Punk");
    UUID aId11 = insererArtiste("u11", "justice", "Justice");
    UUID aId12 = insererArtiste("u12", "moderat", "Moderat");
    insererConcert("Daft Punk", "daft punk", DATE_FUTURE, "bercy", aId10, null);
    insererConcert("Justice", "justice", DATE_FUTURE, "rex", aId11, null);
    insererConcert("Moderat", "moderat", DATE_FUTURE, "trabendo", aId12, null);

    int traites = service.recalculerMatches(null);

    assertTrue(traites >= 3, "Au moins 3 utilisateurs doivent être traités");
    assertEquals(1, compterMatches("u10"), "u10 doit avoir son match");
    assertEquals(1, compterMatches("u11"), "u11 doit avoir son match");
    assertEquals(1, compterMatches("u12"), "u12 doit avoir son match");
  }

  @Test
  void recalculerMatches_salleAvecUuid_matcheViaSalleUuid() {
    insererUser("u20");
    UUID salleUuid = insererSalle("plenitude arena");
    insererUserSalle("u20", salleUuid);
    insererConcert("Coldplay", "coldplay", DATE_FUTURE, "plenitude arena renomme", null, salleUuid);

    service.recalculerMatches(List.of("u20"));

    assertEquals(
        1, compterMatches("u20"), "match attendu via salle_uuid même si salle_cle différent");
  }

  @Test
  void recalculerMatches_salleRenommee_matcheViaUuidApresRenommage() {
    insererUser("u21");
    UUID salleUuid = insererSalle("paris la defense arena");
    insererUserSalle("u21", salleUuid);
    insererConcert("Dua Lipa", "dua lipa", DATE_FUTURE, "plenitude arena", null, salleUuid);

    service.recalculerMatches(List.of("u21"));

    assertEquals(1, compterMatches("u21"), "match attendu via salle_uuid après renommage de salle");
  }

  @Test
  void recalculerMatches_userSansAbonnement_matchesOrphelinsPurges() {
    insererUser("u8");
    insererConcert("Test", "test", DATE_FUTURE, "test", null, null);

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
