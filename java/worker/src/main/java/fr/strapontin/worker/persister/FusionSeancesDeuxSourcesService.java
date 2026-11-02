/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fusion nocturne des séances que deux billetteries publient chacune à leur
 * manière : sous deux intitulés au même créneau, ou l'une sans heure.
 * @since 3.1.2
 * @author Kwaite
 */
@Service
public class FusionSeancesDeuxSourcesService {

  private final NamedParameterJdbcTemplate jdbc;
  private final FusionEvenementsArtisteService fusionEvenements;

  /**
   * @param jdbc accès JDBC
   * @param fusionEvenements fusion d'une paire d'événements
   */
  public FusionSeancesDeuxSourcesService(
      final NamedParameterJdbcTemplate jdbc,
      final FusionEvenementsArtisteService fusionEvenements) {
    this.jdbc = jdbc;
    this.fusionEvenements = fusionEvenements;
  }

  /**
   * Même séance publiée sans heure par une billetterie et à une heure par
   * l'autre : la fiche sans heure rejoint la fiche horodatée quand elles sont
   * les seules, du même artiste, de la même salle et du même jour, de sources
   * différentes, dans une salle non signalée comme mélangeant plusieurs lieux.
   * La fiche horodatée est vivante ; la fiche sans heure peut déjà être absente
   * (la billetterie ne la renvoie plus depuis la publication de l'horaire).
   * @return nombre de fusions effectuées
   */
  public int fusionnerSeancesSansHeure() {
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            """
            WITH ev AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_norm,
                     bool_or(o.source = 'ticketmaster') AS tm, bool_or(o.source = 'eventim') AS evt
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL
                AND (e.absences_consecutives < :seuil OR e.heure IS NULL)
              GROUP BY e.id),
            paires AS (
              SELECT s.id AS sans_heure, h.id AS avec_heure, s.salle_id
              FROM ev s JOIN ev h ON h.salle_id = s.salle_id AND h.date = s.date
                       AND h.artiste_norm = s.artiste_norm
              WHERE s.heure IS NULL AND h.heure IS NOT NULL
                AND NOT (s.tm = h.tm AND s.evt = h.evt))
            SELECT p.sans_heure, p.avec_heure
            FROM paires p JOIN salles sa ON sa.id = p.salle_id
            WHERE (SELECT count(*) FROM paires q WHERE q.sans_heure = p.sans_heure) = 1
              AND (SELECT count(*) FROM paires q WHERE q.avec_heure = p.avec_heure) = 1
              AND\
            """
                + SalleFiable.condition("sa"),
            Map.of("seuil", FusionEvenementsArtisteService.SEUIL_ABSENCES_FICHE_PERIMEE));
    for (final Map<String, Object> paire : paires) {
      fusionEvenements.fusionnerDansFicheVivante(
          (UUID) paire.get("avec_heure"),
          (UUID) paire.get("sans_heure"),
          "même séance, deux sources, une seule heure publiée");
    }
    return paires.size();
  }

  /**
   * Même spectacle sous deux intitulés (« of Mtsensk » / « de Mzensk ») : seuls
   * événements de leur salle au créneau, Ticketmaster seul et Eventim seul, sans
   * artiste reconnu ni fiche annulée. À appeler après les contrôles d'intégrité.
   * @return nombre de fusions effectuées
   */
  public int fusionnerMemeCreneauDeuxSources() {
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            """
            WITH ev AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_id,
                     bool_or(o.source = 'ticketmaster') AS tm, bool_or(o.source = 'eventim') AS evt
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL AND e.heure IS NOT NULL
                AND e.statut IS DISTINCT FROM 'cancelled'
              GROUP BY e.id),
            creneaux AS (
              SELECT salle_id, date, heure FROM ev GROUP BY salle_id, date, heure
              HAVING count(*) = 2
                 AND count(*) FILTER (WHERE tm AND NOT evt) = 1
                 AND count(*) FILTER (WHERE evt AND NOT tm) = 1
                 AND count(*) FILTER (WHERE artiste_id IS NOT NULL) = 0)
            SELECT t.id AS cible, v.id AS source
            FROM creneaux c
            JOIN salles s ON s.id = c.salle_id
            JOIN ev t ON t.salle_id = c.salle_id AND t.date = c.date AND t.heure = c.heure
                     AND t.tm AND NOT t.evt
            JOIN ev v ON v.salle_id = c.salle_id AND v.date = c.date AND v.heure = c.heure
                     AND v.evt AND NOT v.tm
            WHERE\
            """
                + SalleFiable.condition("s"),
            Map.of());
    for (final Map<String, Object> paire : paires) {
      fusionEvenements.fusionnerDansFicheVivante(
          (UUID) paire.get("cible"), (UUID) paire.get("source"), "même créneau, deux sources");
    }
    return paires.size();
  }
}
