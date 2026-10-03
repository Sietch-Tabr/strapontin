/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link PersisteurConcertsDb#alignerHeureReportTM} (alignement d'un report
 * Ticketmaster par manifestation).
 *
 * @since 2.8.6
 * @author Kwaite
 */
class PersisteurConcertsDbTest {

  private static ValeurConcert concert(
      final String url, final LocalTime heure, final String statut) {
    return FabriqueValeurConcert.nouveau()
        .artiste("Artiste")
        .artisteNorm("artiste")
        .date(LocalDate.of(2027, 3, 12))
        .heure(heure)
        .salle("Salle")
        .salleNorm("salle")
        .url(url)
        .source("ticketmaster")
        .statut(statut)
        .construire();
  }

  // Un event TM "package" (VIP/bundle) n'a pas de statut — Set.of(...).contains(null) lève une NPE.
  @Test
  void alignerHeureReportTM_concertPackageStatutNull_neLevePasDException() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db =
        new PersisteurConcertsDb(jdbc, mock(ResolveurArtisteParUrl.class));

    final List<ValeurConcert> concerts =
        List.of(concert("http://tm/pack/123", LocalTime.of(20, 0), null));

    db.alignerHeureReportTM(concerts);

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void alignerHeureReportTM_statutNonReport_ignoreLeConcert() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db =
        new PersisteurConcertsDb(jdbc, mock(ResolveurArtisteParUrl.class));

    final List<ValeurConcert> concerts =
        List.of(concert(URL_IDTIER, LocalTime.of(20, 0), "onsale"));

    db.alignerHeureReportTM(concerts);

    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  private static final String URL_IDTIER =
      "https://www.ticketmaster.fr/fr/manifestation/duran-duran/idmanif/661370/idtier/18864121";
  private static final String URL_IDSEANCE =
      "https://www.ticketmaster.fr/fr/manifestation/duran-duran/idmanif/661370/idseance/4350510";

  private static Map<String, Object> representation(
      final UUID id, final LocalDate date, final LocalTime heure) {
    return Map.of(
        "id",
        id,
        "cle",
        "idmanif:661370",
        "date",
        date.toString(),
        "heure",
        heure.format(DateTimeFormatter.ISO_LOCAL_TIME));
  }

  private static PersisteurConcertsDb db(
      final NamedParameterJdbcTemplate jdbc, final List<Map<String, Object>> existantes) {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(existantes);
    return new PersisteurConcertsDb(jdbc, mock(ResolveurArtisteParUrl.class));
  }

  // Ticketmaster republie le report sous une URL idseance distincte de l'URL idtier connue.
  @Test
  void alignerHeureReportTM_reportSousUneAutreUrlDeLaManifestation_aligneLHeure() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final UUID idExistant = UUID.randomUUID();
    final PersisteurConcertsDb db =
        db(
            jdbc,
            List.of(representation(idExistant, LocalDate.of(2027, 3, 12), LocalTime.of(20, 0))));

    db.alignerHeureReportTM(List.of(concert(URL_IDSEANCE, LocalTime.of(19, 30), "rescheduled")));

    verify(jdbc)
        .update(
            contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    idExistant.toString().equals(params.get("id"))
                        && "19:30".equals(params.get("heure"))
                        && "2027-03-12".equals(params.get("date"))));
  }

  @Test
  void alignerHeureReportTM_reportAUneAutreDateManifestationUnique_aligneDateEtHeure() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final UUID idExistant = UUID.randomUUID();
    final PersisteurConcertsDb db =
        db(
            jdbc,
            List.of(representation(idExistant, LocalDate.of(2027, 3, 13), LocalTime.of(20, 0))));

    db.alignerHeureReportTM(List.of(concert(URL_IDSEANCE, LocalTime.of(20, 0), "rescheduled")));

    verify(jdbc)
        .update(
            contains("UPDATE evenements"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    idExistant.toString().equals(params.get("id"))
                        && "2027-03-12".equals(params.get("date"))));
  }

  // Plusieurs séances le même jour sous une même manifestation : laquelle est reportée est
  // indécidable.
  @Test
  void alignerHeureReportTM_plusieursHeuresLeMemeJour_nAligneRien() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final LocalDate jour = LocalDate.of(2027, 3, 12);
    final PersisteurConcertsDb db =
        db(
            jdbc,
            List.of(
                representation(UUID.randomUUID(), jour, LocalTime.of(15, 0)),
                representation(UUID.randomUUID(), jour, LocalTime.of(20, 0))));

    db.alignerHeureReportTM(List.of(concert(URL_IDSEANCE, LocalTime.of(19, 30), "rescheduled")));

    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void alignerHeureReportTM_autreDatePlusieursRepresentations_nAligneRien() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PersisteurConcertsDb db =
        db(
            jdbc,
            List.of(
                representation(UUID.randomUUID(), LocalDate.of(2027, 3, 13), LocalTime.of(20, 0)),
                representation(UUID.randomUUID(), LocalDate.of(2027, 3, 14), LocalTime.of(20, 0))));

    db.alignerHeureReportTM(List.of(concert(URL_IDSEANCE, LocalTime.of(20, 0), "rescheduled")));

    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void extraireIdmanif_urlTicketmaster_retourneLIdentifiantDeManifestation() {
    assertEquals("661370", AlignementReportTM.extraireIdmanif(URL_IDTIER));
    assertNull(AlignementReportTM.extraireIdmanif("https://www.eventim.fr/x"));
  }
}
