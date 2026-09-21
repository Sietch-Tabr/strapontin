/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Aligne l'heure d'un événement Ticketmaster déjà en base sur celle d'un report
 * entrant — extrait de {@link PersisteurConcertsDb} pour rester sous le seuil
 * de complexité cyclomatique de classe (50).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class AlignementReportTM {

  private static final Logger LOG = LoggerFactory.getLogger(AlignementReportTM.class);

  /**
   * Statuts Ticketmaster signant le remplacement d'une représentation existante
   * (report, annulation) plutôt qu'une représentation supplémentaire.
   */
  private static final Set<String> STATUTS_REPORT_TM =
      Set.of("rescheduled", "cancelled", "postponed");

  private static final String CLE_DATE = "date";
  private static final String CLE_HEURE = "heure";
  private static final int NB_HEURES_DISTINCTES_NON_AMBIGU = 1;

  private final NamedParameterJdbcTemplate jdbc;

  private record CleUrlDate(String url, LocalDate date) {}

  /* package-private : instancié uniquement par PersisteurConcertsDb (même
   * package). */
  AlignementReportTM(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Aligne l'heure d'un événement TM déjà en base sur celle d'un
   * report entrant (détail : CLAUDE.md, section resoudreParUrl).
   * @param concerts concerts après réconciliation, avant upsert
   */
  /* package-private : appelé par PersisteurConcertsDb (même package). */
  void aligner(final List<ValeurConcert> concerts) {
    final List<ValeurConcert> reports =
        concerts.stream()
            .filter(
                c ->
                    c.url() != null
                        && c.heure() != null
                        && "ticketmaster".equals(c.source())
                        && c.statut() != null
                        && STATUTS_REPORT_TM.contains(c.statut()))
            .toList();
    if (reports.isEmpty()) {
      return;
    }

    final List<String> urls = reports.stream().map(ValeurConcert::url).distinct().toList();
    final Map<CleUrlDate, List<Map<String, Object>>> parCle = chargerEvenementsTmParUrlDate(urls);

    for (final ValeurConcert c : reports) {
      alignerHeureSiReport(c, parCle);
    }
  }

  private Map<CleUrlDate, List<Map<String, Object>>> chargerEvenementsTmParUrlDate(
      final List<String> urls) {
    final List<Map<String, Object>> existants =
        jdbc.queryForList(
            """
            SELECT e.id AS id, o.url AS url, e.date AS date, e.heure AS heure
            FROM observations o JOIN evenements e ON e.id = o.evenement_id
            WHERE o.source = 'ticketmaster' AND o.url IN (:urls)
            """,
            Map.of("urls", urls));

    final Map<CleUrlDate, List<Map<String, Object>>> parCle = new HashMap<>();
    for (final Map<String, Object> ligne : existants) {
      final CleUrlDate cle =
          new CleUrlDate((String) ligne.get("url"), ((Date) ligne.get(CLE_DATE)).toLocalDate());
      parCle.computeIfAbsent(cle, _ -> new ArrayList<>()).add(ligne);
    }
    return parCle;
  }

  private void alignerHeureSiReport(
      final ValeurConcert concert, final Map<CleUrlDate, List<Map<String, Object>>> parCle) {
    final List<Map<String, Object>> candidats =
        parCle.get(new CleUrlDate(concert.url(), concert.date()));
    if (candidats == null) {
      return;
    }
    final Set<LocalTime> heuresDistinctes =
        candidats.stream()
            .map(l -> ((Time) l.get(CLE_HEURE)).toLocalTime())
            .collect(Collectors.toSet());
    if (heuresDistinctes.size() != NB_HEURES_DISTINCTES_NON_AMBIGU) {
      return;
    }
    final LocalTime heureExistante = heuresDistinctes.iterator().next();
    if (heureExistante.equals(concert.heure())) {
      return;
    }
    final UUID idExistant = (UUID) candidats.get(0).get("id");
    jdbc.update(
        "UPDATE evenements SET heure = CAST(:heure AS time) WHERE id = CAST(:id AS uuid)",
        Map.of(CLE_HEURE, concert.heure(), "id", idExistant.toString()));
    LOG.info(
        "report Ticketmaster aligné url={} date={} heure {}->{}",
        concert.url(),
        concert.date(),
        heureExistante,
        concert.heure());
  }
}
