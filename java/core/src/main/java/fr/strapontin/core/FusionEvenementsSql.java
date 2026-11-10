/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Règle unique de fusion des événements de deux salles ou de deux artistes, appliquée à
 * l'identique par l'administration (web) et par la fusion automatique nocturne (worker) : les
 * observations de l'événement absorbé sont reportées sur l'événement cible plutôt que perdues
 * (celles qui entrent en conflit sur {@code (evenement_id, source, url)} sont déjà représentées),
 * son plateau est reporté, l'événement vidé est supprimé, les autres événements sont réassignés.
 * @since 3.1.4
 * @author Kwaite
 */
public final class FusionEvenementsSql {

  /** Fusion de deux salles : deux événements se rejoignent s'ils ont même artiste, date, heure. */
  public static final Critere SALLE = new Critere("salle_id", "e1.artiste_norm = e2.artiste_norm");

  /** Fusion de deux artistes : deux événements se rejoignent s'ils ont même salle, date, heure. */
  public static final Critere ARTISTE = new Critere("artiste_id", "e1.salle_id = e2.salle_id");

  private FusionEvenementsSql() {}

  /**
   * Ce qui change entre une fusion de salles et une fusion d'artistes.
   *
   * @param colonne colonne de {@code evenements} qui désigne l'entité fusionnée
   * @param rapprochement condition d'identité de deux événements, hors date et heure
   */
  public record Critere(String colonne, String rapprochement) {}

  /**
   * @param jdbc accès JDBC (dans la transaction de la fusion)
   * @param critere salle ou artiste
   * @param ids paramètres {@code source} et {@code cible} (identifiants texte de l'entité)
   */
  public static void fusionner(
      final NamedParameterJdbcTemplate jdbc, final Critere critere, final Map<String, Object> ids) {
    final String appariement =
        "e1."
            + critere.colonne()
            + " = CAST(:source AS uuid) AND e2."
            + critere.colonne()
            + " = CAST(:cible AS uuid) AND "
            + critere.rapprochement()
            + " AND e1.date = e2.date AND e1.heure IS NOT DISTINCT FROM e2.heure";
    jdbc.update(
        "DELETE FROM observations o1 USING observations o2, evenements e1, evenements e2"
            + " WHERE o1.evenement_id = e1.id AND o2.evenement_id = e2.id AND "
            + appariement
            + " AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url",
        ids);
    // Plateau de l'événement absorbé reporté sur la cible, jamais laissé au CASCADE.
    jdbc.update(
        "INSERT INTO evenement_artistes (evenement_id, artiste_norm, artiste_nom, artiste_id)"
            + " SELECT e2.id, ea.artiste_norm, ea.artiste_nom, ea.artiste_id"
            + " FROM evenement_artistes ea, evenements e1, evenements e2"
            + " WHERE ea.evenement_id = e1.id AND "
            + appariement
            + " ON CONFLICT DO NOTHING",
        ids);
    jdbc.update(
        "UPDATE observations SET evenement_id = e2.id FROM evenements e1, evenements e2"
            + " WHERE observations.evenement_id = e1.id AND "
            + appariement,
        ids);
    jdbc.update(
        "DELETE FROM evenements WHERE "
            + critere.colonne()
            + " = CAST(:source AS uuid) AND NOT EXISTS (SELECT 1 FROM observations"
            + " WHERE observations.evenement_id = evenements.id)",
        ids);
    jdbc.update(
        "UPDATE evenements SET "
            + critere.colonne()
            + " = CAST(:cible AS uuid) WHERE "
            + critere.colonne()
            + " = CAST(:source AS uuid)",
        ids);
  }
}
