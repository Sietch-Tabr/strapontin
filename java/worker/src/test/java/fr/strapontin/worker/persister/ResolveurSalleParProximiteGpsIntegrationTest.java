/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Rattachement par proximité GPS sur PostgreSQL réel : une salle que la même
 * billetterie nomme déjà autrement n'est pas candidate.
 *
 * @since 3.1.3
 * @author Kwaite
 */
class ResolveurSalleParProximiteGpsIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  private ResolveurSalleParProximiteGps resolveur;
  private UUID comedieBastille;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM salles", Map.of());
    resolveur = new ResolveurSalleParProximiteGps(jdbc);
    comedieBastille =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, dept, ville, latitude, longitude,"
                + " origine_position) VALUES ('la comedie bastille paris', 'La Comedie Bastille"
                + " Paris', 'la comedie bastille paris', '75', 'Paris', 48.858905, 2.369950,"
                + " 'sources') RETURNING id",
            Map.of(),
            UUID.class);
  }

  private void declarerLieu(final String source, final String libelle) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, libelle_source)"
            + " VALUES (:salle, :source, :identifiant, :libelle)",
        Map.of(
            "salle", comedieBastille,
            "source", source,
            "identifiant", "internal:la comedie bastille paris",
            "libelle", libelle));
  }

  // Les Caves Saint-Sabin (50 rue Saint-Sabin), à 66 m de La Comédie Bastille : deux lieux réels.
  private static final ClientNominatim.Coordonnees CAVES_SAINT_SABIN =
      new ClientNominatim.Coordonnees(48.858340, 2.370050);

  @Test
  void chercher_salleDejaNommeeAutrementParLaMemeBilletterie_n_estPasCandidate() {
    declarerLieu("eventim", "LA COMEDIE BASTILLE PARIS");

    assertNull(resolveur.chercher(CAVES_SAINT_SABIN, "Les Caves Saint-Sabin", "75", "eventim"));
  }

  // Cas voulu (Ed&n / Espace Dollfus Noack) : la salle ne connaît ce lieu que par une autre
  // billetterie, deux noms sans rapport désignent alors le même endroit.
  @Test
  void chercher_salleConnueSeulementParUneAutreBilletterie_resteCandidate() {
    declarerLieu("ticketmaster", "La Comedie Bastille");

    assertEquals(
        comedieBastille,
        resolveur.chercher(CAVES_SAINT_SABIN, "Les Caves Saint-Sabin", "75", "eventim"));
  }
}
