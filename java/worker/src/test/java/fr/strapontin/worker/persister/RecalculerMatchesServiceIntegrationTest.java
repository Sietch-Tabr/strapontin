/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de [RecalculerMatchesService] avec PostgreSQL réel (Testcontainers).
 *
 * @since 2.0.1
 * @author Kwaite
 */
class RecalculerMatchesServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private RecalculerMatchesService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  private static final String DATE_FUTURE = LocalDate.now().plusMonths(3).toString();
  private static final String DATE_PASSEE = LocalDate.now().minusDays(1).toString();

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM users", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
  }

  private void insererUser(final String id) {
    jdbc.update(
        "INSERT INTO users (id, email) VALUES (:id, :email)",
        Map.of("id", id, "email", id + "@test.fr"));
  }

  private UUID insererArtiste(final String userId, final String norm, final String affiche) {
    final UUID artisteId =
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

  private UUID insererSalle(final String salleCle) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:id, :nom, :norm)"
            + " ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
        Map.of("id", salleCle, "nom", salleCle, "norm", salleCle),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  private UUID insererSousSalle(final String salleCle, final UUID parentId) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, salle_parent_id)"
            + " VALUES (:id, :nom, :norm, CAST(:parentId AS uuid))"
            + " ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
        Map.of("id", salleCle, "nom", salleCle, "norm", salleCle, "parentId", parentId.toString()),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  private void insererUserSalle(final String userId, final UUID salleId) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id)" + " VALUES (:userId, CAST(:salleId AS uuid))",
        Map.of("userId", userId, "salleId", salleId.toString()));
  }

  private void insererConcert(
      final String artiste,
      final String norm,
      final String date,
      final String salleNorm,
      final UUID artisteId,
      final UUID salleId) {
    insererConcert(artiste, norm, date, salleNorm, artisteId, salleId, null);
  }

  private void insererConcert(
      final String artiste,
      final String norm,
      final String date,
      final String salleNorm,
      final UUID artisteId,
      final UUID salleId,
      final String url) {
    insererConcert(artiste, norm, date, salleNorm, artisteId, salleId, url, null);
  }

  private void insererConcert(
      final String artiste,
      final String norm,
      final String date,
      final String salleNorm,
      final UUID artisteId,
      final UUID salleId,
      final String url,
      final String heure) {
    final Map<String, Object> params = new HashMap<>();
    params.put("norm", norm);
    params.put("date", date);
    params.put("salleNorm", salleNorm);
    params.put("artisteId", artisteId != null ? artisteId.toString() : null);
    params.put("salleId", salleId != null ? salleId.toString() : null);
    params.put("heure", heure);
    final UUID evenementId =
        jdbc.queryForObject(
            """
                INSERT INTO evenements (artiste_norm, date, heure, salle_norm, artiste_id, salle_id)
                VALUES (:norm, CAST(:date AS date), CAST(:heure AS time), :salleNorm,
                        CAST(:artisteId AS uuid), CAST(:salleId AS uuid))
                RETURNING id
                """,
            params,
            UUID.class);
    final Map<String, Object> paramsObservation = new HashMap<>();
    paramsObservation.put("id", evenementId.toString());
    paramsObservation.put("artiste", artiste);
    paramsObservation.put("salle", salleNorm);
    paramsObservation.put("url", url);
    jdbc.update(
        """
        INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut, url)
        VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :artiste, :salle, :url)
        """,
        paramsObservation);
  }

  private long compterMatches(final String userId) {
    final Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_matches WHERE user_id = :userId",
            Map.of("userId", userId),
            Long.class);
    return count != null ? count : 0L;
  }

  @Test
  void recalculerMatches_avecArtiste_insereMatch() {
    insererUser("u1");
    final UUID aId = insererArtiste("u1", "julien clerc", "Julien Clerc");
    insererConcert("Julien Clerc", "julien clerc", DATE_FUTURE, "olympia", aId, null);

    service.recalculerMatches(null);

    assertEquals(1, compterMatches("u1"), "Un match doit être inséré pour l'artiste suivi");
  }

  @Test
  void recalculerMatches_avecSalle_insereMatch() {
    insererUser("u2");
    final UUID sId = insererSalle("olympia");
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
    final UUID aId = insererArtiste("u4", "stromae", "Stromae");
    insererConcert("Stromae", "stromae", DATE_PASSEE, "stade", aId, null);

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u4"), "Un concert passé ne doit pas générer de match");
  }

  @Test
  void recalculerMatches_filtreParUserIds_recalculeUniquementUsersSpecifies() {
    insererUser("u5");
    insererUser("u6");
    final UUID aId5 = insererArtiste("u5", "aya nakamura", "Aya Nakamura");
    insererArtiste("u6", "aya nakamura", "Aya Nakamura");
    insererConcert("Aya Nakamura", "aya nakamura", DATE_FUTURE, "accor", aId5, null);

    service.recalculerMatches(List.of("u5"));

    assertEquals(1, compterMatches("u5"), "u5 doit avoir un match");
    assertEquals(0, compterMatches("u6"), "u6 ne doit pas être recalculé");
  }

  @Test
  void recalculerMatches_matchDevenuInvalide_supprime() {
    insererUser("u7");
    final UUID aId = insererArtiste("u7", "indochine", "Indochine");
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
    final UUID aId10 = insererArtiste("u10", "daft punk", "Daft Punk");
    final UUID aId11 = insererArtiste("u11", "justice", "Justice");
    final UUID aId12 = insererArtiste("u12", "moderat", "Moderat");
    insererConcert("Daft Punk", "daft punk", DATE_FUTURE, "bercy", aId10, null);
    insererConcert("Justice", "justice", DATE_FUTURE, "rex", aId11, null);
    insererConcert("Moderat", "moderat", DATE_FUTURE, "trabendo", aId12, null);

    final int traites = service.recalculerMatches(null);

    assertTrue(traites >= 3, "Au moins 3 utilisateurs doivent être traités");
    assertEquals(1, compterMatches("u10"), "u10 doit avoir son match");
    assertEquals(1, compterMatches("u11"), "u11 doit avoir son match");
    assertEquals(1, compterMatches("u12"), "u12 doit avoir son match");
  }

  @Test
  void recalculerMatches_salleAvecUuid_matcheViaSalleId() {
    insererUser("u20");
    final UUID salleId = insererSalle("plenitude arena");
    insererUserSalle("u20", salleId);
    insererConcert("Coldplay", "coldplay", DATE_FUTURE, "plenitude arena renomme", null, salleId);

    service.recalculerMatches(List.of("u20"));

    assertEquals(
        1, compterMatches("u20"), "match attendu via salle_id même si salle_cle différent");
  }

  @Test
  void recalculerMatches_salleRenommee_matcheViaUuidApresRenommage() {
    insererUser("u21");
    final UUID salleId = insererSalle("paris la defense arena");
    insererUserSalle("u21", salleId);
    insererConcert("Dua Lipa", "dua lipa", DATE_FUTURE, "plenitude arena", null, salleId);

    service.recalculerMatches(List.of("u21"));

    assertEquals(1, compterMatches("u21"), "match attendu via salle_id après renommage de salle");
  }

  @Test
  void recalculerMatches_salleIdIdentiqueSalleNormDifferente_matchUniqueParUuid() {
    insererUser("u30");
    final UUID salleId = insererSalle("lempreinte");
    insererUserSalle("u30", salleId);
    // Migration V14 garantit l'unicité par salle_id — un seul concert doit exister
    insererConcert("KrashKarma", "krashkarma", DATE_FUTURE, "lempreinte savigny", null, salleId);

    service.recalculerMatches(List.of("u30"));

    assertEquals(1, compterMatches("u30"), "Un seul match attendu via salle_id unique");
  }

  @Test
  void recalculerMatches_userSansAbonnement_matchesOrphelinsPurges() {
    insererUser("u8");
    insererConcert("Test", "test", DATE_FUTURE, "test", null, null);

    final String concertId =
        jdbc.queryForObject("SELECT id::text FROM concerts LIMIT 1", Map.of(), String.class);
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id) VALUES (:userId, CAST(:concertId AS uuid))",
        Map.of("userId", "u8", "concertId", concertId));

    assertEquals(1, compterMatches("u8"));

    service.recalculerMatches(null);

    assertEquals(0, compterMatches("u8"), "Les matches orphelins doivent être purgés");
  }

  @Test
  void recalculerMatches_packageIsole_pasDeMatch() {
    insererUser("u31");
    final UUID aId = insererArtiste("u31", "jay z", "Jay Z");
    final UUID sId = insererSalle("stade de france");
    insererConcert(
        "Jay Z",
        "jay z",
        DATE_FUTURE,
        "stade de france",
        aId,
        sId,
        "https://tm.example/pack/jay-z-vip");

    service.recalculerMatches(List.of("u31"));

    assertEquals(0, compterMatches("u31"), "Une fiche package isolée ne doit générer aucun match");
  }

  @Test
  void recalculerMatches_packageAvecFicheNormaleMemeEvenement_matchVisible() {
    insererUser("u32");
    final UUID aId = insererArtiste("u32", "jay z", "Jay Z");
    final UUID sId = insererSalle("stade de france");
    insererConcert(
        "Jay Z",
        "jay z",
        DATE_FUTURE,
        "stade de france",
        aId,
        sId,
        "https://tm.example/pack/jay-z-vip",
        "20:00");
    insererConcert(
        "Jay Z",
        "jay z",
        DATE_FUTURE,
        "stade de france",
        aId,
        sId,
        "https://tm.example/jay-z",
        "19:00");

    service.recalculerMatches(List.of("u32"));

    assertEquals(
        2,
        compterMatches("u32"),
        "les deux lignes restent visibles tant que supprimerDoublonsParSalleId ne les a pas"
            + " fusionnées (hors du périmètre de RecalculerMatchesService)");
  }

  @Test
  void recalculerMatches_suiviDuComplexe_remonteLesConcertsDeLaSousSalle() {
    insererUser("u33");
    final UUID complexe = insererSalle("la seine musicale");
    final UUID sousSalle = insererSousSalle("la seine musicale grande seine", complexe);
    insererUserSalle("u33", complexe);
    insererConcert(
        "Artiste Grande Seine",
        "artiste grande seine",
        DATE_FUTURE,
        "la seine musicale grande seine",
        null,
        sousSalle);

    service.recalculerMatches(List.of("u33"));

    assertEquals(
        1, compterMatches("u33"), "suivre le complexe doit remonter les concerts de la sous-salle");
  }

  @Test
  void recalculerMatches_suiviDeLaSousSalle_neRemontePasLesFreres() {
    insererUser("u34");
    final UUID complexe = insererSalle("la seine musicale bis");
    final UUID grandeSeine = insererSousSalle("la seine musicale bis grande seine", complexe);
    final UUID auditorium = insererSousSalle("la seine musicale bis auditorium", complexe);
    insererUserSalle("u34", grandeSeine);
    insererConcert(
        "Artiste Auditorium",
        "artiste auditorium",
        DATE_FUTURE,
        "la seine musicale bis auditorium",
        null,
        auditorium);

    service.recalculerMatches(List.of("u34"));

    assertEquals(
        0,
        compterMatches("u34"),
        "suivre une sous-salle précise ne doit pas remonter les concerts d'une autre sous-salle du"
            + " même complexe");
  }
}
