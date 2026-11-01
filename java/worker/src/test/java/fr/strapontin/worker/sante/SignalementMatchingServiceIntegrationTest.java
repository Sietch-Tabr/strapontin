/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de {@link SignalementMatchingService} avec PostgreSQL réel (Testcontainers)
 * — vérifie le débounce par index unique partiel, non exerçable avec un jdbc mocké.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementMatchingServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  // TICKETMASTER_API_KEY n'est pas seedée en base de test — sans ce mock, la construction du
  // contexte Spring échoue dès qu'une autre classe du contexte dépend de TicketmasterSource
  // (WorkerFetchService), même sans rapport avec ce test.
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private SignalementMatchingService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @Test
  void signaler_memeTypeEtEntiteDeuxFois_neCreeQuUneLigneActive() {
    final UUID entiteId = UUID.randomUUID();

    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);
    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);

    final Long nb =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signalements_matching WHERE entite_id = CAST(:id AS uuid)",
            Map.of("id", entiteId.toString()),
            Long.class);
    assertEquals(1L, nb, "un signalement déjà actif ne doit pas être dupliqué");
  }

  @Test
  void signaler_signalementResolu_permetUnNouveauSignalement() {
    final UUID entiteId = UUID.randomUUID();
    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE entite_id = CAST(:id AS uuid)",
        Map.of("id", entiteId.toString()));

    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);

    final Long nb =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signalements_matching WHERE entite_id = CAST(:id AS uuid)",
            Map.of("id", entiteId.toString()),
            Long.class);
    assertEquals(2L, nb, "un signalement résolu n'empêche pas un nouveau signalement");
  }
}
