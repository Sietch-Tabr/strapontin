/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Règle unique de fusion de deux salles hors événements, appliquée par
 * l'administration (web) et la fusion nocturne (worker) : abonnements, lieux
 * des sources, métadonnées et sous-salles de la salle absorbée.
 * @since 3.1.4
 * @author Kwaite
 */
public final class FusionSallesSql {

  private static final String CLE_SOURCE = "source";
  private static final String CLE_CIBLE = "cible";

  private FusionSallesSql() {}

  /**
   * Les abonnements en double de la salle absorbée sont supprimés, le reste
   * réassigné ; tous ses lieux sont gardés : une salle a souvent plusieurs
   * lieux d'une même source pour un même bâtiment, et la clé (source,
   * identifiant) ne dépend pas de la salle.
   * @param jdbc accès JDBC (dans la transaction de la fusion)
   * @param ids paramètres {@code source} et {@code cible} (identifiants texte)
   * @return les lieux reportés sur la salle conservée, {@code source:identifiant}
   */
  public static List<String> reporterAbonnementsEtLieux(
      final NamedParameterJdbcTemplate jdbc, final Map<String, Object> ids) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE salle_id = CAST(:source AS uuid)
          AND EXISTS (
            SELECT 1 FROM user_salles u2
            WHERE u2.user_id = user_salles.user_id AND u2.salle_id = CAST(:cible AS uuid)
          )
        """,
        ids);
    jdbc.update(
        "UPDATE user_salles SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);
    final List<String> lieuxReportes =
        jdbc.queryForList(
            "SELECT source || ':' || identifiant_externe FROM salle_sources"
                + " WHERE salle_id = CAST(:source AS uuid) ORDER BY 1",
            ids,
            String.class);
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);
    return lieuxReportes;
  }

  /**
   * La cible garde ses métadonnées et récupère celles de la source qu'elle n'a
   * pas ; si elle était elle-même une sous-salle de la source, elle hérite du
   * parent de la source plutôt que de rester rattachée à une salle sur le
   * point d'être supprimée.
   * @param jdbc accès JDBC (dans la transaction de la fusion)
   * @param ids paramètres {@code source} et {@code cible} (identifiants texte)
   */
  public static void reporterMetadonnees(
      final NamedParameterJdbcTemplate jdbc, final Map<String, Object> ids) {
    jdbc.update(
        """
        UPDATE salles SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM salles WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM salles WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);
    jdbc.update(
        "UPDATE salles SET salle_parent_id ="
            + " (SELECT salle_parent_id FROM salles WHERE id = CAST(:source AS uuid))"
            + " WHERE id = CAST(:cible AS uuid) AND salle_parent_id = CAST(:source AS uuid)",
        ids);
  }

  /**
   * Réassigne à la cible chaque sous-salle de la source (hors cible
   * elle-même), sauf celle dont la cible est déjà un descendant : la
   * réassigner créerait un cycle enfant, cible, ..., enfant. Une telle
   * sous-salle est détachée (parent à {@code NULL}) plutôt que laissée
   * pointer vers la source sur le point d'être supprimée.
   * @param jdbc accès JDBC (dans la transaction de la fusion)
   * @param cibleId salle conservée
   * @param sourceId salle absorbée
   * @return les sous-salles détachées, à signaler pour revue manuelle
   */
  public static List<UUID> reassignerSousSallesSansCycle(
      final NamedParameterJdbcTemplate jdbc, final UUID cibleId, final UUID sourceId) {
    final List<UUID> enfants =
        jdbc.query(
            "SELECT id FROM salles WHERE salle_parent_id = CAST(:source AS uuid) AND id !="
                + " CAST(:cible AS uuid)",
            Map.of(CLE_SOURCE, sourceId.toString(), CLE_CIBLE, cibleId.toString()),
            (rs, _) -> rs.getObject("id", UUID.class));
    final java.util.ArrayList<UUID> detaches = new java.util.ArrayList<>();
    for (final UUID enfant : enfants) {
      if (SalleHierarchie.creeraitCycle(cibleId, enfant, id -> lireSalleParentId(jdbc, id))) {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = NULL WHERE id = CAST(:id AS uuid)",
            Map.of("id", enfant.toString()));
        detaches.add(enfant);
      } else {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = CAST(:cible AS uuid) WHERE id = CAST(:id AS"
                + " uuid)",
            Map.of(CLE_CIBLE, cibleId.toString(), "id", enfant.toString()));
      }
    }
    return detaches;
  }

  // Un accès direct à la liste : findFirst() lève une NullPointerException sur un flux dont
  // l'unique élément est null, cas d'une salle racine sans parent.
  private static UUID lireSalleParentId(final NamedParameterJdbcTemplate jdbc, final UUID salleId) {
    final List<UUID> resultat =
        jdbc.query(
            "SELECT salle_parent_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            (rs, _) -> (UUID) rs.getObject("salle_parent_id"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }
}
