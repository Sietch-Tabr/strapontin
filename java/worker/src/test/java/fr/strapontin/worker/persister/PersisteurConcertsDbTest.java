/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link PersisteurConcertsDb#alignerHeureReportTM}.
 *
 * @since 2.8.6
 * @author Kwaite
 */
class PersisteurConcertsDbTest {

  private static ValeurConcert concert(
      final String url, final LocalTime heure, final String statut) {
    return new ValeurConcert(
        "Artiste",
        "artiste",
        LocalDate.of(2027, 3, 12),
        heure,
        "Salle",
        "salle",
        null,
        null,
        url,
        "ticketmaster",
        null,
        null,
        statut,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // Un event TM "package" (VIP/bundle) n'a pas de statut — Set.of(...).contains(null) lève une NPE.
  @Test
  void alignerHeureReportTM_concertPackageStatutNull_neLevePasDException() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db = new PersisteurConcertsDb(jdbc);

    final List<ValeurConcert> concerts =
        List.of(concert("http://tm/pack/123", LocalTime.of(20, 0), null));

    db.alignerHeureReportTM(concerts);

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void alignerHeureReportTM_statutNonReport_ignoreLeConcert() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db = new PersisteurConcertsDb(jdbc);

    final List<ValeurConcert> concerts =
        List.of(concert("http://tm/123", LocalTime.of(20, 0), "onsale"));

    db.alignerHeureReportTM(concerts);

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void alignerHeureReportTM_reportAvecUneSeuleHeureExistante_aligneLaLigneExistante() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db = new PersisteurConcertsDb(jdbc);
    final UUID idExistant = UUID.randomUUID();

    final Map<String, Object> ligneExistante =
        Map.of(
            "id",
            idExistant,
            "url",
            "http://tm/123",
            "date",
            Date.valueOf(LocalDate.of(2027, 3, 12)),
            "heure",
            Time.valueOf(LocalTime.of(19, 0)));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneExistante));

    final List<ValeurConcert> concerts =
        List.of(concert("http://tm/123", LocalTime.of(20, 30), "rescheduled"));

    db.alignerHeureReportTM(concerts);

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    idExistant.toString().equals(params.get("id"))
                        && LocalTime.of(20, 30).equals(params.get("heure"))));
  }
}
