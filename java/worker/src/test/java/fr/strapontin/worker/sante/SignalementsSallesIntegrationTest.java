/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.maintenance.WorkerPurgeService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration des signalements de salles et de leur cycle de vie
 * (PostgreSQL réel) : référence de doublon à une salle disparue, décisions
 * admin mémorisées, levée des signalements devenus caducs.
 *
 * @since 3.0.1
 * @author Kwaite
 */
class SignalementsSallesIntegrationTest extends AbstractWorkerIntegrationTest {

  // TICKETMASTER_API_KEY n'est pas seedée en base de test : sans ce mock, la construction du
  // contexte Spring échoue (WorkerFetchService dépend de TicketmasterSource).
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private CoherenceMultiSalleService coherence;
  @Autowired private ControleIntegriteService controleIntegrite;
  @Autowired private WorkerPurgeService purge;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    jdbc.update("DELETE FROM salles_paires_distinctes", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
  }

  private UUID insererSalle(final String nom, final Double latitude, final Double longitude) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, latitude, longitude)"
            + " VALUES (:cle, :nom, :nom, '75', :lat, :lon) RETURNING id",
        new MapSqlParameterSource()
            .addValue("cle", nom)
            .addValue("nom", nom)
            .addValue("lat", latitude)
            .addValue("lon", longitude),
        UUID.class);
  }

  private void insererSourceInterne(
      final UUID salleId, final String nomSource, final Double latitude, final Double longitude) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), 'eventim', :identifiant, :lat, :lon)",
        new MapSqlParameterSource()
            .addValue("id", salleId.toString())
            .addValue("identifiant", "internal:" + nomSource)
            .addValue("lat", latitude)
            .addValue("lon", longitude));
  }

  private String contaminationSignalee(final UUID salleId) {
    return jdbc.queryForObject(
        "SELECT contamination_signalee FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()),
        String.class);
  }

  private UUID doublonSignale(final UUID salleId) {
    return jdbc.queryForObject(
        "SELECT doublon_signale FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()),
        UUID.class);
  }

  // Une salle fusionnée ou purgée laissait le doublon signalé sur l'autre salle pointer vers un
  // UUID mort : la suppression doit lever la référence, quel que soit le chemin de suppression.
  @Test
  void suppressionDeLaSalleReferencee_leveLeDoublonSignale() {
    final UUID salleA = insererSalle("le tube", null, null);
    final UUID salleB = insererSalle("le tube seignosse", null, null);
    jdbc.update(
        "UPDATE salles SET doublon_signale = CAST(:b AS uuid) WHERE id = CAST(:a AS uuid)",
        Map.of("a", salleA.toString(), "b", salleB.toString()));

    jdbc.update("DELETE FROM salles WHERE id = CAST(:id AS uuid)", Map.of("id", salleB.toString()));

    assertNull(doublonSignale(salleA));
  }

  // Un nom d'usage sans rapport textuel ("P M C Strasbourg" pour le Palais de la Musique et des
  // Congrès) désigne le même lieu quand la source le situe à la position de la salle.
  @Test
  void detecterContamination_sourceSitueeALaPositionDeLaSalle_neSignalePas() {
    final UUID salle = insererSalle("palais des congres salle erasme", 48.5933, 7.7549);
    insererSourceInterne(salle, "p m c strasbourg", 48.5936, 7.7551);

    coherence.detecterContamination();

    assertNull(contaminationSignalee(salle));
  }

  @Test
  void detecterContamination_sourceEloignee_signale() {
    final UUID salle = insererSalle("le 13e art", 48.8303, 2.3553);
    insererSourceInterne(salle, "eglise lutherienne de la trinite paris 13", 48.8375, 2.3667);

    coherence.detecterContamination();

    assertEquals("eglise lutherienne de la trinite paris 13", contaminationSignalee(salle));
  }

  // Une source déclarée légitime par un admin n'est plus jamais re-signalée, et la signature
  // déjà posée est levée au cycle suivant.
  @Test
  void detecterContamination_sourceAcceptee_leveEtNeResignalePlus() {
    final UUID salle = insererSalle("agen agora", null, null);
    insererSourceInterne(salle, "centre des congres agen", null, null);
    coherence.detecterContamination();
    assertNotNull(contaminationSignalee(salle));

    jdbc.update(
        "UPDATE salle_sources SET contamination_acceptee_le = now()"
            + " WHERE salle_id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    coherence.detecterContamination();

    assertNull(contaminationSignalee(salle));
  }

  @Test
  void detecterDoublons_paireDeclareeDistincte_neSignalePas() {
    final UUID salleA = insererSalle("theatre montparnasse", null, null);
    final UUID salleB = insererSalle("theatre de poche montparnasse", null, null);
    final boolean ordre = salleA.toString().compareTo(salleB.toString()) < 0;
    jdbc.update(
        "INSERT INTO salles_paires_distinctes (salle_a, salle_b)"
            + " VALUES (CAST(:a AS uuid), CAST(:b AS uuid))",
        Map.of(
            "a", (ordre ? salleA : salleB).toString(), "b", (ordre ? salleB : salleA).toString()));

    assertEquals(0, coherence.detecterDoublons());
    assertNull(doublonSignale(salleA));
    assertNull(doublonSignale(salleB));
  }

  // Une sous-salle rattachée à son complexe après coup n'est plus un doublon : le signalement
  // déjà posé était conservé indéfiniment.
  @Test
  void detecterDoublons_sousSalleRattacheeDepuis_leveLeSignalementCaduc() {
    final UUID complexe = insererSalle("le tube", null, null);
    final UUID sousSalle = insererSalle("le tube les bourdaines", null, null);
    coherence.detecterDoublons();
    final UUID porteur = doublonSignale(complexe) != null ? complexe : sousSalle;
    assertNotNull(doublonSignale(porteur));

    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:p AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("p", complexe.toString(), "id", sousSalle.toString()));
    coherence.detecterDoublons();

    assertNull(doublonSignale(complexe));
    assertNull(doublonSignale(sousSalle));
  }

  // Un signalement source_orpheline dont la salle a désormais un événement est clos
  // automatiquement.
  @Test
  void verifier_sourceOrphelineDevenueRattachee_fermeLeSignalement() {
    final UUID salle = insererSalle("salle orpheline", null, null);
    insererSourceInterne(salle, "salle orpheline", null, null);
    controleIntegrite.verifier();
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id)"
            + " VALUES ('artiste', :date, 'salle orpheline', CAST(:id AS uuid))",
        new MapSqlParameterSource()
            .addValue("date", LocalDate.now().plusDays(10))
            .addValue("id", salle.toString()));

    controleIntegrite.verifier();

    final Long actifs =
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching"
                + " WHERE type = 'source_orpheline' AND resolu_le IS NULL",
            Map.of(),
            Long.class);
    assertEquals(0L, actifs);
  }

  // Une salle suivie sans événement annoncé est le cas normal (on la suit pour être prévenu) :
  // signalée, elle encombrait la file de revue ; le signalement déjà posé est clos.
  @Test
  void verifier_sourceSansEvenementDUneSalleSuivie_neSignalePas() {
    final UUID salle = insererSalle("salle suivie", null, null);
    insererSourceInterne(salle, "salle suivie", null, null);
    controleIntegrite.verifier();
    jdbc.update(
        "INSERT INTO users (id, email) VALUES ('u-orph', 'u-orph@test.fr')"
            + " ON CONFLICT DO NOTHING",
        Map.of());
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES ('u-orph', CAST(:id AS uuid))",
        Map.of("id", salle.toString()));

    controleIntegrite.verifier();

    final Long actifs =
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching"
                + " WHERE type = 'source_orpheline' AND resolu_le IS NULL",
            Map.of(),
            Long.class);
    assertEquals(0L, actifs);
  }

  // Un flag de suppression posé puis dont la condition ne tient plus (concert reconfirmé)
  // restait indéfiniment dans la file de revue manuelle.
  @Test
  void purgerConcertsNonConfirmes_flagDevenuCaduc_estLeve() {
    final UUID salle = insererSalle("salle flag", null, null);
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id, absences_consecutives,"
            + " suppression_suggeree_le) VALUES ('artiste flag', :date, 'salle flag',"
            + " CAST(:id AS uuid), 0, now() - interval '2 days')",
        new MapSqlParameterSource()
            .addValue("date", LocalDate.now().plusDays(10))
            .addValue("id", salle.toString()));

    purge.executerPurgerConcertsNonConfirmes();

    final Long flagues =
        jdbc.queryForObject(
            "SELECT count(*) FROM evenements WHERE suppression_suggeree_le IS NOT NULL",
            Map.of(),
            Long.class);
    assertEquals(0L, flagues);
  }
}
