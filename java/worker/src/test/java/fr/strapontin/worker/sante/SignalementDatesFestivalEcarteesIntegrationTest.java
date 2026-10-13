/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.ProduitEcarte;
import fr.strapontin.sources.SourceAvecProduitsEcartes;
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
    final SourceAvecProduitsEcartes eventim = mock(SourceAvecProduitsEcartes.class);
    when(eventim.preleverProduitsEcartes())
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
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT absences_consecutives FROM evenements WHERE id = CAST(:id AS uuid)",
            Map.of("id", faux.toString()),
            Integer.class),
        "le produit écarté n'est plus observé : une absence par cycle");
  }

  private int signalerProduitEcartePourSuzane(final String url) {
    final SourceAvecProduitsEcartes eventim = mock(SourceAvecProduitsEcartes.class);
    when(eventim.preleverProduitsEcartes())
        .thenReturn(List.of(new ProduitEcarte("Suzane", url, "Couplé Chapiteau + Magic 13")));
    return new SignalementDatesFestivalEcartees(List.of(eventim), jdbc, signalements).signaler();
  }

  // Billet couplé : seconde observation du concert, à côté de son billet normal.
  @Test
  void signaler_concertAyantUnAutreBillet_nEstPasSignale() {
    final UUID concert = evenement("https://www.eventim.fr/couple-13", "2098-10-13");
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('eventim' AS source_concert),"
            + " 'https://www.eventim.fr/suzane-13', 'Suzane', 'Chapiteau')",
        Map.of("id", concert.toString()));

    assertEquals(0, signalerProduitEcartePourSuzane("https://www.eventim.fr/couple-13"));
  }

  // Produit écarté pour Suzane, mais concert de Yael Naim : sa date légitime.
  @Test
  void signaler_concertDUnAutreArtisteIdentifie_nEstPasSignale() {
    final UUID concert = evenement("https://www.eventim.fr/soniques-14", "2098-11-14");
    final UUID yael =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('Yael Naim', 'yael naim') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "UPDATE evenements SET artiste_id = CAST(:a AS uuid), artiste_norm = 'yael naim'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("a", yael.toString(), "id", concert.toString()));

    assertEquals(0, signalerProduitEcartePourSuzane("https://www.eventim.fr/soniques-14"));
  }
}
