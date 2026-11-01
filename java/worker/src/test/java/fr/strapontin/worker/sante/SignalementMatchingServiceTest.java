/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link SignalementMatchingService}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementMatchingServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SignalementMatchingService service;
  private LogsAdminWorker logsAdminWorker;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new SignalementMatchingService(jdbc, new ObjectMapper(), logsAdminWorker);
  }

  @Test
  void signaler_nominal_insereEnBase() {
    final UUID entiteId = UUID.randomUUID();

    service.signaler(
        "collision_wikidata_artiste", "artiste", entiteId, "haute", Map.of("wikidataId", "Q1"));

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params ->
                    "collision_wikidata_artiste".equals(params.getValue("type"))
                        && entiteId.toString().equals(params.getValue("entiteId"))));
  }

  @Test
  void signaler_detailsNull_neLevePasException() {
    final UUID entiteId = UUID.randomUUID();

    service.signaler("wikidata_ambiguite", "artiste", entiteId, "basse", null);

    verify(jdbc).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void signaler_echecInsertion_neLevePasException() {
    org.mockito.Mockito.doThrow(new RuntimeException("boom"))
        .when(jdbc)
        .update(anyString(), any(SqlParameterSource.class));

    service.signaler("collision_wikidata_artiste", "artiste", UUID.randomUUID(), "haute", null);
  }

  private static List<UUID> entites(final int nombre) {
    final List<UUID> entites = new ArrayList<>();
    for (int i = 0; i < nombre; i++) {
      entites.add(UUID.randomUUID());
    }
    return entites;
  }

  // 4 716 signalements en un passage : la régression doit se voir dès le premier.
  @Test
  void clore_volumeAnormalDeSignalementsActifs_averti() {
    service.clore("seance_dans_deux_salles", entites(201));

    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("warn"),
            anyString(),
            org.mockito.ArgumentMatchers.contains("seance_dans_deux_salles"),
            any());
  }

  @Test
  void clore_volumeHabituel_neAvertitPas() {
    service.clore("seance_dans_deux_salles", entites(200));

    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), any());
  }
}
