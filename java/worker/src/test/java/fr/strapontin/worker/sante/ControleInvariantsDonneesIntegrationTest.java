/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Invariants objectifs des données ({@link ControleInvariantsDonnees}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ControleInvariantsDonneesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private ControleInvariantsDonnees controle;

  @BeforeEach
  void setUp() {
    controle = new ControleInvariantsDonnees(jdbc, signalements);
  }

  private UUID creerSalle() {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('l embarcadere', 'L''Embarcadère',"
            + " 'l embarcadere') RETURNING id",
        Map.of(),
        UUID.class);
  }

  private void evenement(final UUID salle, final String artiste, final String dept) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
            + " VALUES (:artiste, CURRENT_DATE + 30, '20:00', 'l embarcadere',"
            + " CAST(:salle AS uuid), :dept)",
        Map.of("artiste", artiste, "salle", salle.toString(), "dept", dept));
  }

  private List<String> actifs() {
    return jdbc.queryForList(
        "SELECT type FROM signalements_matching WHERE resolu_le IS NULL ORDER BY type",
        Map.of(),
        String.class);
  }

  // L'Embarcadère : une seule source, concerts d'Aubervilliers (93) et de Boulogne-sur-Mer (62).
  @Test
  void verifier_salleSurDeuxDepartements_signaleEtRefermeUneFoisCorrigee() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");

    controle.verifier();
    assertEquals(List.of("salle_multi_departements"), actifs());

    jdbc.update("DELETE FROM evenements WHERE dept = '62'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_salleContamineeSuivie_signaleLImpactUtilisateur() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u1', 'u1', 'u1@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", salle.toString()));

    controle.verifier();
    controle.verifier();

    assertEquals(List.of("salle_multi_departements", "salle_suivie_contaminee"), actifs());
  }

  @Test
  void verifier_compteActiveSansIdentifiantDeConnexion_signaleUneIncoherence() {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u2', 'u2', 'u2@test.fr', false)",
        Map.of());

    controle.verifier();

    assertEquals(List.of("comptes_incoherents"), actifs());
  }

  @Test
  void verifier_donneesSaines_aucunSignalement() {
    evenement(creerSalle(), "artiste", "75");

    assertEquals(0, controle.verifier());
    assertEquals(List.of(), actifs());
  }
}
