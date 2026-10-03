/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Suspicion d'un identifiant d'artiste ({@link SuiviSanteSourcesService}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class SuiviSanteSourcesServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SuiviSanteSourcesService suivi;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM artiste_sources", Map.of());
  }

  private void artisteTicketmaster(final String attractionId, final String verifieIlYa) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nom)",
        Map.of("id", id.toString(), "nom", "artiste " + id));
    jdbc.update(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe,"
            + " vivacite_verifiee_le) VALUES (CAST(:id AS uuid), 'ticketmaster', :attraction,"
            + " now() - CAST(:ilYa AS interval))",
        Map.of("id", id.toString(), "attraction", attractionId, "ilYa", verifieIlYa));
  }

  private boolean suspect(final String attractionId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT suspect_depuis IS NOT NULL FROM artiste_sources"
                + " WHERE identifiant_externe = :id",
            Map.of("id", attractionId),
            Boolean.class));
  }

  private void troisRecuperationsVides(final String attractionId) {
    for (int i = 0; i < 3; i++) {
      suivi.enregistrerResultatArtistesLot(List.of(attractionId), List.of());
    }
  }

  // Artiste suivi sans date annoncée : il redevenait suspect chaque jour.
  @Test
  void enregistrerResultatArtistesLot_artisteVerifieVivantLaVeille_neRedevientPasSuspect() {
    artisteTicketmaster("K-VIVANT", "1 day");

    troisRecuperationsVides("K-VIVANT");

    assertFalse(suspect("K-VIVANT"));
  }

  @Test
  void enregistrerResultatArtistesLot_verificationAncienne_redevientSuspect() {
    artisteTicketmaster("K-ANCIEN", "10 days");

    troisRecuperationsVides("K-ANCIEN");

    assertTrue(suspect("K-ANCIEN"));
  }
}
