/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vue unifiée des signalements de matching (table dédiée et mécanismes
 * établis) ; un faux positif jugé par un admin n'est plus re-signalé.
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SignalementsAdminService {

  private static final int TAILLE_PAGE_DEFAUT = 50;
  // Quatre mécanismes réunis, entité nommée par jointure selon son type.
  private static final String SQL_LISTE =
      """
      SELECT t.*, COALESCE(s.nom_affiche, s.nom, a.nom_affiche, a.nom, u.username,
               NULLIF(concat_ws(' · ', c.artiste, to_char(c.date, 'DD/MM/YYYY'), c.salle), ''),
               CASE WHEN t.entite_type = 'departement'
                    THEN 'Département ' || (t.details::jsonb ->> 'departement') END)
               AS entite_nom,
             NULLIF(concat_ws(' · ', COALESCE(o.nom_affiche, o.nom),
                              NULLIF(concat_ws(' ', o.ville, o.dept), '')), '') AS autre_salle_nom
      FROM (
        SELECT 'nouveau' AS categorie, sm.id::text AS id, sm.type AS type,
               sm.entite_type AS entite_type, sm.entite_id::text AS entite_id,
               sm.confiance AS confiance, sm.detecte_le AS detecte_le,
               NULLIF((sm.details - 'salleExistante' - 'autreSalleId')::text, '{}') AS details,
               sm.details ->> 'userId' AS utilisateur_id,
               COALESCE(sm.details ->> 'salleExistante', sm.details ->> 'autreSalleId')
                   AS autre_salle_id
        FROM signalements_matching sm WHERE sm.resolu_le IS NULL
        UNION ALL
        SELECT 'necessite_intervention', a.artiste_id::text, 'necessite_intervention',
               'artiste', a.artiste_id::text, 'moyenne', a.necessite_intervention_le, NULL, NULL,
               NULL
        FROM artiste_sources a WHERE a.necessite_intervention_le IS NOT NULL
        UNION ALL
        SELECT 'contamination_salle', s.id::text, 'contamination_salle', 'salle',
               s.id::text, 'moyenne', NULL, s.contamination_signalee, NULL, NULL
        FROM salles s WHERE s.contamination_signalee IS NOT NULL
        UNION ALL
        SELECT 'doublon_salle', s.id::text, 'doublon_salle', 'salle', s.id::text, 'moyenne',
               NULL, NULL, NULL, s.doublon_signale::text
        FROM salles s WHERE s.doublon_signale IS NOT NULL
      ) t
      LEFT JOIN salles s ON t.entite_type = 'salle' AND s.id::text = t.entite_id
      LEFT JOIN salles o ON o.id::text = t.autre_salle_id
      LEFT JOIN artistes a ON t.entite_type = 'artiste' AND a.id::text = t.entite_id
      LEFT JOIN concerts c ON t.entite_type = 'evenement' AND c.id::text = t.entite_id
      -- L'entité d'un signalement est un UUID, dérivé de l'identifiant texte du compte.
      LEFT JOIN users u ON t.entite_type = 'utilisateur'
                       AND u.id = COALESCE(t.utilisateur_id, t.entite_id)
      ORDER BY t.detecte_le DESC NULLS LAST
      LIMIT :taille OFFSET :offset
      """;
  private static final String CLE_ID = "id";
  private static final String ENTITE_SALLE = "salle";
  private static final String NIVEAU_INFO = "info";
  private static final String SOURCE_ADMIN = "admin";
  // Signalements dont l'action attendue est la suppression du concert désigné.
  private static final List<String> TYPES_CONCERT_A_SUPPRIMER =
      List.of("date_festival_ecartee", "concert_double_salles_distinctes");

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param logAdminService lecture des mécanismes de signalement non migrés
   */
  public SignalementsAdminService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste paginée des signalements actifs, du plus récent au plus ancien
   * (entrées sans horodatage dédié en dernier) — total via une requête
   * séparée, plus lisible que {@code COUNT(*) OVER()} sur 4 sous-requêtes.
   * @param page numéro de page (0-indexé)
   * @return page de signalements actifs
   */
  public ReponseSignalements lister(final int page) {
    final int pageNum = Math.max(0, page);
    final Map<String, Object> paramsPage =
        Map.of("taille", TAILLE_PAGE_DEFAUT, "offset", pageNum * TAILLE_PAGE_DEFAUT);
    final List<EntreeSignalement> signalements =
        jdbc.query(SQL_LISTE, paramsPage, (rs, _) -> lireEntree(rs));

    final long total = totalActifs();
    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseSignalements(
        signalements, pageNum, totalPages, total, CatalogueSignalements.tous());
  }

  private static EntreeSignalement lireEntree(final ResultSet rs) throws SQLException {
    final OffsetDateTime detecteLe = rs.getObject("detecte_le", OffsetDateTime.class);
    return new EntreeSignalement(
        rs.getString("categorie"),
        rs.getString("id"),
        rs.getString("type"),
        new EntreeSignalement.EntiteSignalee(
            rs.getString("entite_type"), rs.getString("entite_id"), rs.getString("entite_nom")),
        rs.getString("confiance"),
        detecteLe != null ? detecteLe.toInstant() : null,
        rs.getString("details"),
        rs.getString("autre_salle_nom") != null
            ? new EntreeSignalement.EntiteSignalee(
                ENTITE_SALLE, rs.getString("autre_salle_id"), rs.getString("autre_salle_nom"))
            : null);
  }

  private long totalActifs() {
    final Long total =
        jdbc.queryForObject(
            """
            SELECT
              (SELECT COUNT(*) FROM signalements_matching WHERE resolu_le IS NULL)
              + (SELECT COUNT(*) FROM artiste_sources WHERE necessite_intervention_le IS NOT NULL)
              + (SELECT COUNT(*) FROM salles WHERE contamination_signalee IS NOT NULL)
              + (SELECT COUNT(*) FROM salles WHERE doublon_signale IS NOT NULL)
            """,
            Map.of(),
            Long.class);
    return total != null ? total : 0;
  }

  /**
   * Marque un signalement de la table {@code signalements_matching} comme
   * résolu manuellement ; pour un département sans concert, l'absence de
   * couverture est aussi reconnue (plus de signalement tant qu'il reste vide).
   *
   * @param id identifiant numérique du signalement
   * @return {@code true} si un signalement actif correspondant a été résolu
   */
  public boolean resoudre(final long id) {
    final List<String> departements =
        jdbc.queryForList(
            "SELECT details ->> 'departement' FROM signalements_matching WHERE id = :id"
                + " AND resolu_le IS NULL AND type = 'departement_sans_concert'",
            Map.of(CLE_ID, id),
            String.class);
    final int lignes =
        jdbc.update(
            "UPDATE signalements_matching SET resolu_le = now(), resolu_manuellement = true"
                + " WHERE id = :id AND resolu_le IS NULL",
            Map.of("id", id));
    if (lignes > 0 && !departements.isEmpty()) {
      // Absence reconnue : le département n'est plus signalé tant qu'il reste sans
      // concert.
      jdbc.update(
          "UPDATE couverture_departements SET couverture_connue_le = now() WHERE dept = :dept",
          Map.of("dept", departements.get(0)));
    }
    if (lignes > 0) {
      logAdminService.logAdmin(
          NIVEAU_INFO,
          SOURCE_ADMIN,
          "signalement résolu manuellement",
          Map.of("signalementId", id));
    }
    return lignes > 0;
  }

  /**
   * Supprime le concert d'un signalement actif qui le désigne comme à
   * supprimer, et clôt le signalement.
   * @param id identifiant numérique du signalement
   * @return {@code true} si le concert a été supprimé
   */
  @Transactional
  public boolean supprimerConcertSignale(final long id) {
    final List<String> concerts =
        jdbc.queryForList(
            "SELECT entite_id::text FROM signalements_matching WHERE id = :id"
                + " AND resolu_le IS NULL AND entite_type = 'evenement'"
                + " AND type IN (:types)",
            Map.of(CLE_ID, id, "types", TYPES_CONCERT_A_SUPPRIMER),
            String.class);
    if (concerts.isEmpty()) {
      return false;
    }
    jdbc.update(
        "DELETE FROM evenements WHERE id = CAST(:concert AS uuid)",
        Map.of("concert", concerts.get(0)));
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE id = :id", Map.of(CLE_ID, id));
    logAdminService.logAdmin(
        NIVEAU_INFO,
        SOURCE_ADMIN,
        "concert signalé supprimé",
        Map.of("signalementId", id, "concertId", concerts.get(0)));
    return true;
  }

  /**
   * Aligne la position d'une salle sur son élément Wikidata, à partir des
   * coordonnées relevées par le signalement « position de source éloignée »,
   * puis le clôt. La salle est dès lors réalignée chaque nuit sur l'élément.
   * @param id identifiant numérique du signalement
   * @return {@code true} si la position a été alignée
   */
  @Transactional
  public boolean alignerPositionSurWikidata(final long id) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT entite_id::text AS salle,"
                + " CAST(details ->> 'latitudeWikidata' AS double precision) AS latitude,"
                + " CAST(details ->> 'longitudeWikidata' AS double precision) AS longitude"
                + " FROM signalements_matching WHERE id = :id AND resolu_le IS NULL"
                + " AND type = 'position_source_eloignee'",
            Map.of(CLE_ID, id));
    if (lignes.isEmpty() || lignes.getFirst().get("latitude") == null) {
      return false;
    }
    final Map<String, Object> ligne = lignes.getFirst();
    jdbc.update(
        "UPDATE salles SET latitude = :latitude, longitude = :longitude,"
            + " origine_position = 'wikidata' WHERE id = CAST(:salle AS uuid)",
        Map.of(
            "latitude",
            ligne.get("latitude"),
            "longitude",
            ligne.get("longitude"),
            ENTITE_SALLE,
            ligne.get(ENTITE_SALLE)));
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE id = :id", Map.of(CLE_ID, id));
    logAdminService.logAdmin(
        NIVEAU_INFO,
        SOURCE_ADMIN,
        "position de salle alignée sur Wikidata",
        Map.of("signalementId", id, "salleId", ligne.get(ENTITE_SALLE)));
    return true;
  }

  /**
   * Marque les sources internes actuellement signalées d'une salle comme
   * légitimes (nom d'usage, sigle officiel du même lieu) — plus jamais
   * re-signalées tant que leur identifiant ne change pas.
   * @param salleId salle signalée
   * @return {@code true} si un signalement de contamination actif a été levé
   */
  @Transactional
  public boolean accepterContamination(final UUID salleId) {
    final List<String> signatures =
        jdbc.queryForList(
            "SELECT contamination_signalee FROM salles"
                + " WHERE id = CAST(:id AS uuid) AND contamination_signalee IS NOT NULL",
            Map.of(CLE_ID, salleId.toString()),
            String.class);
    if (signatures.isEmpty()) {
      return false;
    }
    // Nom du lieu sans préfixe ni code postal : formes historique et qualifiée.
    final List<String> noms = Arrays.asList(signatures.get(0).split("\\|"));
    jdbc.update(
        "UPDATE salle_sources SET contamination_acceptee_le = now()"
            + " WHERE salle_id = CAST(:id AS uuid) AND identifiant_externe LIKE 'internal:%'"
            + " AND split_part(substring(identifiant_externe FROM 10), '@', 1) IN (:noms)",
        Map.of(CLE_ID, salleId.toString(), "noms", noms));
    jdbc.update(
        "UPDATE salles SET contamination_signalee = NULL WHERE id = CAST(:id AS uuid)",
        Map.of(CLE_ID, salleId.toString()));
    logAdminService.logAdmin(
        NIVEAU_INFO,
        SOURCE_ADMIN,
        "contamination de salle déclarée faux positif",
        Map.of("salleId", salleId.toString(), "sources", String.join(", ", noms)));
    return true;
  }

  /**
   * Déclare distinctes deux salles signalées comme doublon potentiel — la
   * paire n'est plus jamais re-signalée.
   * @param salleId salle portant le signalement
   * @return {@code true} si un signalement de doublon actif a été levé
   */
  @Transactional
  public boolean declarerSallesDistinctes(final UUID salleId) {
    final List<UUID> partenaires =
        jdbc.queryForList(
            "SELECT doublon_signale FROM salles"
                + " WHERE id = CAST(:id AS uuid) AND doublon_signale IS NOT NULL",
            Map.of(CLE_ID, salleId.toString()),
            UUID.class);
    if (partenaires.isEmpty()) {
      return false;
    }
    final UUID partenaire = partenaires.get(0);
    // Ordre textuel, celui de PostgreSQL sur uuid (CHECK salle_a < salle_b).
    final boolean ordre = salleId.toString().compareTo(partenaire.toString()) < 0;
    jdbc.update(
        "INSERT INTO salles_paires_distinctes (salle_a, salle_b)"
            + " VALUES (CAST(:a AS uuid), CAST(:b AS uuid)) ON CONFLICT DO NOTHING",
        Map.of(
            "a", (ordre ? salleId : partenaire).toString(),
            "b", (ordre ? partenaire : salleId).toString()));
    jdbc.update(
        "UPDATE salles SET doublon_signale = NULL WHERE id = CAST(:id AS uuid)",
        Map.of(CLE_ID, salleId.toString()));
    logAdminService.logAdmin(
        NIVEAU_INFO,
        SOURCE_ADMIN,
        "salles déclarées distinctes",
        Map.of("salleA", salleId.toString(), "salleB", partenaire.toString()));
    return true;
  }
}
