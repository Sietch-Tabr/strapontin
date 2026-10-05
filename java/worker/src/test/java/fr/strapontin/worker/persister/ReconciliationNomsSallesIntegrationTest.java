/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Renommage de salles par {@link ReconciliationNomsSalles#absorberRenommagesSalles} sur base
 * réelle : source de référence et stabilité du nom d'un cycle à l'autre.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ReconciliationNomsSallesIntegrationTest extends AbstractWorkerIntegrationTest {

  // Le bean réel échoue au démarrage sans TICKETMASTER_API_KEY en base.
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  private ReconciliationNomsSalles reconciliation;
  private UUID salleId;

  @BeforeEach
  void setUp() {
    reconciliation = new ReconciliationNomsSalles(jdbc);
    salleId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), 'l olympia', 'L''Olympia', 'l olympia', '75')",
        Map.of("id", salleId.toString()));
  }

  private void mapper(final String source, final String identifiant) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), :source, :identifiant)",
        Map.of("id", salleId.toString(), "source", source, "identifiant", identifiant));
  }

  private void absorber(final String source, final String identifiant, final String nom) {
    final ValeurConcert concert =
        FabriqueValeurConcert.nouveau()
            .artiste("Stromae")
            .artisteNorm("stromae")
            .date(LocalDate.now().plusMonths(2))
            .salle(nom)
            .salleNorm(nom.toLowerCase())
            .ville("Paris")
            .dept("75")
            .source(source)
            .salleSourceId(identifiant)
            .construire();
    reconciliation.absorberRenommagesSalles(
        List.of(concert), Map.of(identifiant, salleId), Map.of());
  }

  private String nom() {
    return jdbc.queryForObject(
        "SELECT nom FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()),
        String.class);
  }

  @Test
  void absorberRenommagesSalles_mappingUniqueRenommeEnSource_appliqueLeNouveauNom() {
    mapper("ticketmaster", "ticketmaster:olympia");

    absorber("ticketmaster", "ticketmaster:olympia", "L'Olympia Bruno Coquatrix");

    assertEquals("L'Olympia Bruno Coquatrix", nom());
    assertEquals(
        "l olympia bruno coquatrix",
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            String.class));
  }

  @Test
  void absorberRenommagesSalles_nomEventimDUneSalleAvecMappingTicketmaster_neRenommePas() {
    mapper("ticketmaster", "ticketmaster:olympia");
    mapper("eventim", "eventim:internal:lolympia");

    absorber("eventim", "eventim:internal:lolympia", "LOlympia");

    assertEquals("L'Olympia", nom());
  }

  @Test
  void absorberRenommagesSalles_deuxMappingsMemeSourceScoreEgal_nomStableEntreLesCycles() {
    mapper("eventim", "eventim:internal:salle pleyel");
    mapper("eventim", "eventim:internal:salle pleyel paris 08");
    jdbc.update(
        "UPDATE salles SET nom = 'Salle Pleyel' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    absorber("eventim", "eventim:internal:salle pleyel paris 08", "Salle Pleyel Paris 08");
    assertEquals("Salle Pleyel", nom());
    absorber("eventim", "eventim:internal:salle pleyel", "Salle Pleyel");
    assertEquals("Salle Pleyel", nom());
  }
}
