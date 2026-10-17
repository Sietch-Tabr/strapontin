/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.sources.DepartementsConnus;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Couverture des départements sur base réelle : signalement d'un département sans concert,
 * absence reconnue par l'admin, retour de concerts.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class CouvertureDepartementsIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private CouvertureDepartements couverture;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM couverture_departements", Map.of());
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    couverture = new CouvertureDepartements(jdbc, signalements);
  }

  private void concertAVenir(final String dept) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, dept)"
            + " VALUES (:artiste, CURRENT_DATE + 20, 'salle', :dept)",
        Map.of("artiste", "artiste " + UUID.randomUUID(), "dept", dept));
  }

  private boolean signale(final String dept) {
    return jdbc.queryForObject(
        "SELECT count(*) > 0 FROM signalements_matching WHERE type = 'departement_sans_concert'"
            + " AND resolu_le IS NULL AND details ->> 'departement' = :dept",
        Map.of("dept", dept),
        Boolean.class);
  }

  @Test
  void verifier_departementSansConcert_estSignaleEtLesAutresNeLePasSontPas() {
    concertAVenir("75");

    final int signales = couverture.verifier();

    assertTrue(signale("973"));
    assertFalse(signale("75"));
    assertEquals(DepartementsConnus.codes().size() - 1, signales);
  }

  @Test
  void verifier_absenceReconnue_neResignalePasLeDepartement() {
    couverture.verifier();
    jdbc.update(
        "UPDATE couverture_departements SET couverture_connue_le = now() WHERE dept = '973'",
        Map.of());
    jdbc.update("UPDATE signalements_matching SET resolu_le = now()", Map.of());

    couverture.verifier();

    assertFalse(signale("973"));
    assertTrue(signale("974"));
  }

  @Test
  void verifier_departementQuiRetrouveDesConcertsPuisLesPerd_estSignaleDeNouveau() {
    couverture.verifier();
    jdbc.update(
        "UPDATE couverture_departements SET couverture_connue_le = now() WHERE dept = '973'",
        Map.of());
    jdbc.update("UPDATE signalements_matching SET resolu_le = now()", Map.of());

    concertAVenir("973");
    couverture.verifier();
    assertFalse(signale("973"));

    jdbc.update("DELETE FROM evenements", Map.of());
    couverture.verifier();

    assertTrue(signale("973"));
  }

  @Test
  void verifier_departementQuiRetrouveDesConcerts_refermeSonSignalement() {
    couverture.verifier();
    assertTrue(signale("971"));

    concertAVenir("971");
    couverture.verifier();

    assertFalse(signale("971"));
  }
}
