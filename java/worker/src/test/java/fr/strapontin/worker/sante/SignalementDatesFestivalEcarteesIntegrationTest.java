/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import fr.strapontin.sources.ProduitEcarte;
import fr.strapontin.sources.SourceAvecProduitsEcartes;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class SignalementDatesFestivalEcarteesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private UUID evenement(final String url, final String date) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm)"
                + " VALUES ('suzane', CAST(:date AS date), '20:00', 'parc des expositions')"
                + " RETURNING id",
            Map.of("date", date),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('eventim' AS source_concert), :url, 'Suzane',"
            + " 'Parc des Expositions')",
        Map.of("id", id.toString(), "url", url));
    return id;
  }

  @Test
  void signaler_concertEnregistreSousUnProduitEcarte_estSignale() {
    final UUID faux = evenement("https://www.eventim.fr/soniques-14-11", "2098-11-14");
    evenement("https://www.eventim.fr/suzane-13-11", "2098-11-13");
    final SourceConcerts eventim =
        mock(SourceConcerts.class, withSettings().extraInterfaces(SourceAvecProduitsEcartes.class));
    when(((SourceAvecProduitsEcartes) eventim).preleverProduitsEcartes())
        .thenReturn(
            List.of(
                new ProduitEcarte(
                    "Suzane",
                    "https://www.eventim.fr/soniques-14-11",
                    "Les Rendez-vous Soniques #17")));

    final int signales =
        new SignalementDatesFestivalEcartees(List.of(eventim), jdbc, signalements).signaler();

    assertEquals(1, signales);
    assertEquals(
        List.of(faux),
        jdbc.queryForList(
            "SELECT entite_id FROM signalements_matching WHERE type = 'date_festival_ecartee'",
            Map.of(),
            UUID.class));
  }
}
