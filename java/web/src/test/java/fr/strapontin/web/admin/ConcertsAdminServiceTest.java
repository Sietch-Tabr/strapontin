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

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link ConcertsAdminService}.
 *
 * @since 2.8.3
 * @author Kwaite
 */
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
  void lister_nominal_retourneLesConcertsFlaguesAvecTotal() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              final RowMapper<ConcertSuppressionSuggeree> mapper = invocation.getArgument(2);
              final ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
              when(rs.getString("artiste")).thenReturn("Vincent Dedienne");
              when(rs.getString("salle")).thenReturn("Le Forum - Salle Gounod");
              when(rs.getString("ville")).thenReturn("Fréjus");
              when(rs.getObject("date", LocalDate.class)).thenReturn(LocalDate.of(2027, 5, 23));
              when(rs.getInt("absences_consecutives")).thenReturn(50);
              when(rs.getTimestamp("suppression_suggeree_le"))
                  .thenReturn(Timestamp.from(Instant.now()));
              when(rs.getLong("total_filtre")).thenReturn(1L);
              return List.of(mapper.mapRow(rs, 0));
            });

    final ReponseConcertsAConfirmer reponse = service.lister(0);

    assertEquals(1, reponse.concerts().size());
    assertEquals("Vincent Dedienne", reponse.concerts().get(0).artiste());
    assertEquals(1L, reponse.total());
  }

  // page au-delà du total (offset trop grand) : aucune ligne ne porte total_filtre, un second
  // aller-retour COUNT reste nécessaire pour ne pas afficher un total à zéro trompeur.
  @Test
  @SuppressWarnings("unchecked")
  void lister_pageAuDelaDuTotal_retombeSurUnCountSepare() {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), org.mockito.ArgumentMatchers.eq(Long.class)))
        .thenReturn(2L);

    final ReponseConcertsAConfirmer reponse = service.lister(3);

    assertEquals(2L, reponse.total());
    assertTrue(reponse.concerts().isEmpty());
  }

  @Test
  void confirmerSuppression_concertFlague_supprimeEtLogue() {
    final UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final boolean resultat = service.confirmerSuppression(id);

    assertTrue(resultat);
    verify(logAdminService)
        .logAdmin(org.mockito.ArgumentMatchers.eq("info"), anyString(), anyString(), anyMap());
  }

  @Test
  void confirmerSuppression_concertInconnuOuNonFlague_neSupprimeRienEtNeLoguePas() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    final boolean resultat = service.confirmerSuppression(UUID.randomUUID());

    assertFalse(resultat);
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void annulerSuppression_concertFlague_restaureEtLogue() {
    final UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final boolean resultat = service.annulerSuppression(id);

    assertTrue(resultat);
    verify(logAdminService)
        .logAdmin(org.mockito.ArgumentMatchers.eq("info"), anyString(), anyString(), anyMap());
  }

  @Test
  void annulerSuppression_concertInconnuOuNonFlague_neRestaureRienEtNeLoguePas() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    final boolean resultat = service.annulerSuppression(UUID.randomUUID());

    assertFalse(resultat);
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }
}
