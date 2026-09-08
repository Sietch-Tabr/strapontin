/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/// Tests d'intégration de [FusionSallesClePorteeService] avec PostgreSQL réel (Testcontainers).
///
/// @since 2.8.4
/// @author Kwaite
class FusionSallesClePorteeServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  private static final String DATE_FUTURE = LocalDate.now().plusMonths(2).toString();

  // TICKETMASTER_API_KEY n'est pas seedée en base de test — sans ce mock, la construction du
  // contexte Spring échoue dès qu'une autre classe du contexte dépend de TicketmasterSource
  // (WorkerFetchService), même sans rapport avec ce test (même motif que
  // PersisterConcertsIntegrationTest).
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private FusionSallesClePorteeService fusionSallesClePortee;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM concerts", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
  }

  private UUID creerSalle(String nom, String dept, String wikidataId) {
    return creerSalle(nom, dept, wikidataId, null, null);
  }

  private UUID creerSalle(
      String nom, String dept, String wikidataId, Double latitude, Double longitude) {
    UUID id = UUID.randomUUID();
    // wikidataId/latitude/longitude peuvent être null (Map.of rejette les valeurs nulles) —
    // MapSqlParameterSource accepte les valeurs nulles sans lever d'exception.
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept, wikidata_id, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), :cle, :nom, :nomNorm, :dept, :wikidataId, :latitude,"
            + " :longitude)",
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("cle", nom.toLowerCase())
            .addValue("nom", nom)
            .addValue("nomNorm", nom.toLowerCase())
            .addValue("dept", dept)
            .addValue("wikidataId", wikidataId)
            .addValue("latitude", latitude)
            .addValue("longitude", longitude));
    return id;
  }

  private void creerConcert(String artisteNorm, String heure, String dept, UUID salleId) {
    jdbc.update(
        "INSERT INTO concerts (artiste, artiste_norm, date, heure, salle, salle_norm, dept,"
            + " source, salle_id)"
            + " VALUES (:artiste, :artisteNorm, CAST(:date AS date), CAST(:heure AS time),"
            + " 'Salle', 'salle', :dept, CAST('ticketmaster' AS source_concert),"
            + " CAST(:salleId AS uuid))",
        Map.of(
            "artiste", artisteNorm,
            "artisteNorm", artisteNorm,
            "date", DATE_FUTURE,
            "heure", heure,
            "dept", dept,
            "salleId", salleId.toString()));
  }

  @Test
  void fusionner_deuxSallesEtabliesPartageantUnConcert_fusionneEtPreserveWikidataId() {
    // Cas réel documenté (#1099) : "Grand Theatre Limoges" (Eventim, sans wikidata_id) et
    // "Opera de Limoges" (TM, wikidata_id connu) désignent le même bâtiment — aucun rapport
    // textuel entre les deux noms, seul un concert partagé (artiste+date+heure+dept) le révèle.
    UUID grandTheatre = creerSalle("Grand Theatre Limoges", "87", null);
    UUID operaLimoges = creerSalle("Opera de Limoges", "87", "Q3354551");
    creerConcert("laurent voulzy", "20:30", "87", grandTheatre);
    creerConcert("laurent voulzy", "20:30", "87", operaLimoges);

    int fusions = fusionSallesClePortee.fusionner();

    assertEquals(1, fusions);
    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "Une seule salle doit subsister après fusion");
    Long nbConcerts = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(
        1L, nbConcerts, "Les deux concerts partageant la même clé forte doivent être dédupliqués");
    String wikidataId =
        jdbc.queryForObject("SELECT wikidata_id FROM salles", Map.of(), String.class);
    assertEquals(
        "Q3354551", wikidataId, "Le wikidata_id déjà connu doit être préservé après fusion");
  }

  @Test
  void fusionner_fusionEffectuee_journaliseDansLogsAdmin() {
    // Une fusion automatique et destructive ne doit jamais rester silencieuse.
    UUID a = creerSalle("Grand Theatre Limoges", "87", null);
    UUID b = creerSalle("Opera de Limoges", "87", "Q3354551");
    creerConcert("laurent voulzy", "20:30", "87", a);
    creerConcert("laurent voulzy", "20:30", "87", b);

    fusionSallesClePortee.fusionner();

    Long nbLogs =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE source = 'reconciliation-nocturne' AND"
                + " message LIKE '%fusionnées%'",
            Map.of(), Long.class);
    assertEquals(1L, nbLogs);
  }

  @Test
  void fusionner_aucuneSalleAvecConcertPartage_neFusionneRien() {
    creerSalle("Salle A", "87", null);
    creerSalle("Salle B", "87", null);

    int fusions = fusionSallesClePortee.fusionner();

    assertEquals(0, fusions);
    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  // Un seul concert coïncidant par erreur ne doit jamais suffire à fusionner deux salles
  // réellement distinctes.
  @Test
  void fusionner_sallesReellementDistinctesConcertCoincident_neFusionnePas() {
    UUID arena = creerSalle("Arena du Pays d'Aix", "13", null);
    UUID pasino = creerSalle("Pasino Grand Aix-en-Provence", "13", null);
    creerConcert("collision upstream", "21:00", "13", arena);
    creerConcert("collision upstream", "21:00", "13", pasino);

    int fusions = fusionSallesClePortee.fusionner();

    assertEquals(0, fusions, "des noms sans rapport ne doivent pas fusionner malgré la clé forte");
    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  // Garde-fou GPS (#1177/#1180) : un nom similaire et une clé forte partagée ne doivent jamais
  // suffire si les coordonnées connues placent les deux salles à plusieurs centaines de mètres
  // l'une de l'autre (complexe multi-salles, ou deux bâtiments distincts par coïncidence).
  @Test
  void fusionner_coordonneesEloigneesNomsSimilaires_neFusionnePas() {
    UUID salleParis = creerSalle("La Seine Musicale", "92", null, 48.8296, 2.2404);
    UUID salleMarseille = creerSalle("La Seine Musicale Marseille", "13", null, 43.2965, 5.3698);
    creerConcert("collision upstream", "21:00", "92", salleParis);
    creerConcert("collision upstream", "21:00", "13", salleMarseille);

    int fusions = fusionSallesClePortee.fusionner();

    assertEquals(
        0,
        fusions,
        "des coordonnées distantes doivent bloquer la fusion malgré la clé forte et le nom");
    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  @Test
  void fusionner_coordonneesProchesNomsSimilaires_fusionne() {
    UUID salleA = creerSalle("Grand Theatre Limoges", "87", null, 45.8336, 1.2611);
    UUID salleB = creerSalle("Opera de Limoges", "87", "Q3354551", 45.8337, 1.2612);
    creerConcert("laurent voulzy", "20:30", "87", salleA);
    creerConcert("laurent voulzy", "20:30", "87", salleB);

    int fusions = fusionSallesClePortee.fusionner();

    assertEquals(1, fusions, "des coordonnées proches ne doivent pas bloquer une fusion légitime");
  }
}
