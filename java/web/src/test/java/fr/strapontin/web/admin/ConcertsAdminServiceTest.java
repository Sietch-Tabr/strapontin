/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link ConcertsAdminService}.
///
/// @since 2.8.3
/// @author Kwaite
class ConcertsAdminServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private ConcertsAdminService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    service = new ConcertsAdminService(jdbc, logAdminService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lister_nominal_retourneLesConcertsFlagues() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(
            List.of(
                new ConcertSuppressionSuggeree(
                    UUID.randomUUID(),
                    "Vincent Dedienne",
                    "Le Forum - Salle Gounod",
                    "Fréjus",
                    LocalDate.of(2027, 5, 23),
                    null,
                    50,
                    Instant.now())));

    List<ConcertSuppressionSuggeree> resultat = service.lister();

    assertEquals(1, resultat.size());
    assertEquals("Vincent Dedienne", resultat.get(0).artiste());
  }

  @Test
  void confirmerSuppression_concertFlague_supprimeEtLogue() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean resultat = service.confirmerSuppression(id);

    assertTrue(resultat);
    verify(logAdminService)
        .logAdmin(org.mockito.ArgumentMatchers.eq("info"), anyString(), anyString(), anyMap());
  }

  @Test
  void confirmerSuppression_concertInconnuOuNonFlague_neSupprimeRienEtNeLoguePas() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    boolean resultat = service.confirmerSuppression(UUID.randomUUID());

    assertFalse(resultat);
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void annulerSuppression_concertFlague_restaureEtLogue() {
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean resultat = service.annulerSuppression(id);

    assertTrue(resultat);
    verify(logAdminService)
        .logAdmin(org.mockito.ArgumentMatchers.eq("info"), anyString(), anyString(), anyMap());
  }

  @Test
  void annulerSuppression_concertInconnuOuNonFlague_neRestaureRienEtNeLoguePas() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    boolean resultat = service.annulerSuppression(UUID.randomUUID());

    assertFalse(resultat);
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }
}
