/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Aligne date et heure d'un événement Ticketmaster sur un report entrant de
 * la même manifestation (idmanif, à défaut URL) : TM republie un report sous
 * une autre URL, qui créerait sinon un second événement à l'horaire obsolète.
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

  private static final Pattern MOTIF_IDMANIF = Pattern.compile("/idmanif/(\\d+)");
  private static final String CLE_DATE = "date";
  private static final String CLE_HEURE = "heure";
  private static final String CLE_ID = "id";

  private static final int UNIQUE = 1;

  private final NamedParameterJdbcTemplate jdbc;

  private record Representation(UUID id, LocalDate date, LocalTime heure) {}

  /* package-private : instancié uniquement par PersisteurConcertsDb (même
   * package). */
  AlignementReportTM(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Même date : heure alignée si une seule y est connue. Autre date : date et
   * heure alignées si la manifestation n'a qu'un événement. Jamais vers un
   * créneau déjà occupé par le même artiste dans la même salle.
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

    final List<String> cles = reports.stream().map(c -> cleFiche(c.url())).distinct().toList();
    final Map<String, List<Representation>> parCle = chargerRepresentations(cles);

    for (final ValeurConcert c : reports) {
      final List<Representation> candidats = parCle.get(cleFiche(c.url()));
      if (candidats != null) {
        aligner(c, candidats);
      }
    }
  }

  /* package-private : visibilité élargie pour AlignementReportTMTest (même package). */
  static String extraireIdmanif(final String url) {
    if (url == null) {
      return null;
    }
    final Matcher correspondance = MOTIF_IDMANIF.matcher(url);
    return correspondance.find() ? correspondance.group(1) : null;
  }

  // Manifestation si l'URL en porte une, sinon l'URL exacte.
  private static String cleFiche(final String url) {
    final String idmanif = extraireIdmanif(url);
    return idmanif != null ? "idmanif:" + idmanif : "url:" + url;
  }

  // Les fiches « package » (/pack/) partagent l'idmanif sans être une séance.
  private Map<String, List<Representation>> chargerRepresentations(final List<String> cles) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT e.id AS id, e.date::text AS date, e.heure::text AS heure,
                   COALESCE('idmanif:' || substring(o.url FROM '/idmanif/([0-9]+)'),
                            'url:' || o.url) AS cle
            FROM observations o JOIN evenements e ON e.id = o.evenement_id
            WHERE o.source = 'ticketmaster'
              AND o.url NOT LIKE '%/pack/%'
              AND COALESCE('idmanif:' || substring(o.url FROM '/idmanif/([0-9]+)'),
                           'url:' || o.url) IN (:cles)
            """,
            Map.of("cles", cles));
    final Map<String, List<Representation>> parIdmanif = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      final String heure = (String) ligne.get(CLE_HEURE);
      parIdmanif
          .computeIfAbsent((String) ligne.get("cle"), _ -> new ArrayList<>())
          .add(
              new Representation(
                  (UUID) ligne.get(CLE_ID),
                  LocalDate.parse((String) ligne.get(CLE_DATE)),
                  heure != null ? LocalTime.parse(heure) : null));
    }
    return parIdmanif;
  }

  // Même date : une seule heure distincte (sinon séances multiples, ambigu) ;
  // autre date : une seule représentation connue de la manifestation.
  private static Representation choisirCible(
      final List<Representation> memeDate, final List<Representation> candidats) {
    if (memeDate.isEmpty()) {
      return candidats.size() == UNIQUE ? candidats.get(0) : null;
    }
    final Set<LocalTime> heures =
        memeDate.stream().map(Representation::heure).collect(Collectors.toSet());
    return heures.size() == UNIQUE ? memeDate.get(0) : null;
  }

  // Une date reportée est une nouvelle information pour qui suit l'artiste ou la
  // salle : la correspondance déjà notifiée repart au prochain envoi.
  private void reArmerNotifications(final Representation cible, final ValeurConcert report) {
    if (cible.date().equals(report.date())) {
      return;
    }
    jdbc.update(
        "UPDATE user_matches SET notification_envoyee_le = NULL"
            + " WHERE concert_id = CAST(:id AS uuid) AND notification_envoyee_le IS NOT NULL",
        Map.of(CLE_ID, cible.id().toString()));
  }

  private void aligner(final ValeurConcert report, final List<Representation> candidats) {
    final List<Representation> memeDate =
        candidats.stream().filter(r -> r.date().equals(report.date())).toList();
    final Representation cible = choisirCible(memeDate, candidats);
    if (cible == null) {
      return;
    }
    if (cible.date().equals(report.date()) && report.heure().equals(cible.heure())) {
      return;
    }
    final int modifies =
        jdbc.update(
            """
            UPDATE evenements e SET date = CAST(:date AS date), heure = CAST(:heure AS time)
            WHERE e.id = CAST(:id AS uuid)
              AND NOT EXISTS (
                SELECT 1 FROM evenements x
                WHERE x.id <> e.id AND x.artiste_norm = e.artiste_norm
                  AND x.salle_id IS NOT DISTINCT FROM e.salle_id
                  AND x.date = CAST(:date AS date) AND x.heure = CAST(:heure AS time))
            """,
            Map.of(
                CLE_DATE,
                report.date().toString(),
                CLE_HEURE,
                report.heure().toString(),
                CLE_ID,
                cible.id().toString()));
    if (modifies > 0) {
      reArmerNotifications(cible, report);
      LOG.info(
          "report Ticketmaster aligné url={} {} {} -> {} {}",
          report.url(),
          cible.date(),
          cible.heure(),
          report.date(),
          report.heure());
    }
  }
}
