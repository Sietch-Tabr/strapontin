/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Tests unitaires pour {@link SignalementMatchingService}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementMatchingServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SignalementMatchingService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new SignalementMatchingService(jdbc, new ObjectMapper());
  }

  @Test
  void signaler_nominal_insereEnBase() {
    UUID entiteId = UUID.randomUUID();

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
    UUID entiteId = UUID.randomUUID();

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
}
