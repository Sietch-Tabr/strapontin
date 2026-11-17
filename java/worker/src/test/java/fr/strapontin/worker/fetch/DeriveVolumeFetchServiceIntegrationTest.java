/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.maintenance.WorkerStatsService;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Dérive de volume sur base réelle : un retry ciblé n'entre pas dans la moyenne de référence et
 * n'est pas comparé à un cycle complet.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class DeriveVolumeFetchServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private WorkerStatsService stats;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
    jdbc.update("DELETE FROM fetch_jobs_historique", Map.of());
  }

  private void cycleComplet(final int volume) {
    stats.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(volume, 0), null, false);
  }

  private long avertissementsDerive() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM logs_admin WHERE source = 'derive-volume'", Map.of(), Long.class);
  }

  @Test
  void enregistrerStatJob_retryCibleAVolumeFaible_neSignaleAucuneDerive() {
    for (int i = 0; i < 5; i++) {
      cycleComplet(700);
    }

    stats.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(57, 0), null, true);

    assertEquals(0, avertissementsDerive());
  }

  @Test
  void enregistrerStatJob_retryCiblesPrecedents_nAbaissentPasLaMoyenneDeReference() {
    for (int i = 0; i < 5; i++) {
      cycleComplet(700);
    }
    stats.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(57, 0), null, true);
    stats.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(144, 0), null, true);

    // Une vraie chute reste détectée, comparée à 700 et non à une moyenne tirée vers le bas.
    cycleComplet(300);

    assertEquals(1, avertissementsDerive());
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT count(*) FROM fetch_jobs_historique WHERE retry_cible", Map.of(), Long.class));
  }
}
