/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion du référentiel artistes pour l'onglet admin : liste paginée, édition du nom affiché,
 * fusion de doublons.
 *
 * <p>La fusion dédoublonne désormais les concerts en conflit avant remapping, à l'image de {@link
 * ReferentielSallesService#fusionner} : même si la contrainte unique {@code
 * uq_concerts_artiste_date_heure_salle} porte sur {@code artiste_norm} (texte, jamais en conflit
 * lors d'un remapping d'{@code artiste_id}), deux artistes distincts fusionnés peuvent chacun
 * avoir enregistré le même concert réel (même salle, même date/heure) sous un {@code artiste_norm}
 * textuel différent selon la source — sans ce nettoyage, les deux lignes subsistaient après
 * fusion, dupliquant le concert sur la fiche de l'artiste cible.
 *
 * <p>Le statut affiché est binaire ({@code a_jour}/{@code invalide}) : contrairement à {@link
 * ReferentielSallesService} (3 états basés sur {@code suspect_depuis}, compteur passif jugé fiable
 * pour les salles), le statut artiste dérive de {@code artiste_sources.necessite_intervention_le},
 * posée uniquement quand la vérification active nocturne a confirmé l'identifiant mort ET échoué à
 * en retrouver un nouveau — le compteur passif seul (trop de faux positifs sur des artistes sans
 * date à venir) ne suffit plus à qualifier un état intermédiaire {@code a_verifier}.
 *
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class ReferentielArtistesService {

  private static final int TAILLE_PAGE_DEFAUT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public ReferentielArtistesService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste paginée du référentiel artistes, filtrée par nom/statut.
   *
   * @param page numéro de page (0-indexé)
   * @param nom filtre nom partiel (insensible à la casse), peut être null
   * @param statut filtre {@code "a_jour"}/{@code "invalide"} (statut binaire — {@code
   *   "a_verifier"} n'est jamais retourné pour un artiste), peut être null
   * @param wikidataManquant si {@code true}, ne retourne que les artistes sans {@code wikidata_id}
   *   — facilite le repérage des candidats à corriger manuellement sans parcourir toute la
   *   pagination
   * @return page de résultats
   */
  public ReponseReferentielArtistes lister(
      final int page, final String nom, final String statut, final boolean wikidataManquant) {
    final int pageNum = Math.max(0, page);
    final StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    final Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND nom ILIKE :nom ");
      params.put("nom", "%" + nom.strip() + "%");
    }
    if (statut != null && !statut.isBlank()) {
      where.append(" AND statut = :statut ");
      params.put("statut", statut.strip());
    }
    if (wikidataManquant) {
      where.append(" AND wikidata_id IS NULL ");
    }

    final Map<String, Object> paramsPage = new HashMap<>(params);
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    final long[] total = {0};
    final List<ArtisteReferentiel> artistes =
        jdbc.query(
            CTE_ARTISTES_AGREGES
                + "SELECT id, nom, nom_affiche, wikidata_id, deezer_id, nb_concerts, sources,"
                + " statut, tm_identifiant_externe, COUNT(*) OVER() AS total_filtre FROM"
                + " artistes_agr"
                + where
                + " ORDER BY nb_concerts DESC, nom ASC LIMIT :taille OFFSET :offset",
            paramsPage,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new ArtisteReferentiel(
                  rs.getObject("id", UUID.class),
                  rs.getString("nom"),
                  rs.getString("nom_affiche"),
                  rs.getLong("nb_concerts"),
                  lireSources(rs.getString("sources")),
                  rs.getString("statut"),
                  rs.getString("wikidata_id"),
                  rs.getString("deezer_id"),
                  rs.getString("tm_identifiant_externe"));
            });

    // page vide (offset au-delà du total) : total_filtre n'est renvoyé par aucune ligne, un
    // second aller-retour reste alors nécessaire pour connaître le total réel.
    if (artistes.isEmpty() && pageNum > 0) {
      final Long totalVide =
          jdbc.queryForObject(
              CTE_ARTISTES_AGREGES + "SELECT COUNT(*) FROM artistes_agr" + where,
              params,
              Long.class);
      total[0] = totalVide != null ? totalVide : 0;
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total[0] / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielArtistes(artistes, pageNum, totalPages, total[0]);
  }

  private static List<String> lireSources(final String sourcesConcat) {
    if (sourcesConcat == null || sourcesConcat.isBlank()) {
      return List.of();
    }
    return Arrays.stream(sourcesConcat.split(",")).toList();
  }

  private static final String CTE_ARTISTES_AGREGES =
      """
      WITH artistes_agr AS (
        SELECT a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id,
               COUNT(DISTINCT c.id) FILTER (WHERE c.date >= CURRENT_DATE) AS nb_concerts,
               COALESCE(string_agg(DISTINCT asrc.source, ','), '') AS sources,
               CASE
                 WHEN MIN(asrc.necessite_intervention_le) IS NULL THEN 'a_jour'
                 ELSE 'invalide'
               END AS statut,
               MAX(CASE WHEN asrc.source = 'ticketmaster' THEN asrc.identifiant_externe END)
                 AS tm_identifiant_externe
        FROM artistes a
        LEFT JOIN concerts c ON c.artiste_id = a.id
        LEFT JOIN artiste_sources asrc ON asrc.artiste_id = a.id
        GROUP BY a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id
      )
      """;

  /**
   * Efface l'enrichissement automatique d'un artiste ({@code wikidata_id}, {@code deezer_id},
   * {@code nom_affiche}) pour le remettre candidat à une nouvelle découverte au prochain cycle
   * nocturne — nécessaire pour corriger une mauvaise correspondance automatique, l'enrichissement
   * ne pouvant jamais s'auto-corriger via {@code COALESCE}.
   *
   * @param artisteId UUID de l'artiste
   * @return {@code true} si l'artiste existe et a été réinitialisé
   */
  public boolean reinitialiserEnrichissement(final UUID artisteId) {
    final int nb =
        jdbc.update(
            """
                UPDATE artistes SET wikidata_id = NULL, deezer_id = NULL, nom_affiche = NULL
                WHERE id = CAST(:id AS uuid)
                """,
            Map.of("id", artisteId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "enrichissement artiste réinitialisé",
          Map.of("artisteId", artisteId.toString()));
    }
    return nb > 0;
  }

  /**
   * Efface {@code necessite_intervention_le} sur toutes les sources d'un artiste sans fusion ni
   * changement d'identifiant — action minimale pour un admin qui juge la divergence de nom TM
   * acceptable (ex. convention d'affichage différente, cf. -M-/Matthieu Chédid).
   *
   * <p>{@code nom_tm_ignore} est aligné sur {@code nom_tm_incoherent} (le nom TM à l'origine de la
   * divergence courante) pour que {@code ReconciliationNocturneService} ne resignale pas la même
   * divergence à chaque cycle nocturne — seul un nouveau changement de nom TM redéclenchera le
   * flag.
   *
   * @param artisteId UUID de l'artiste
   * @return {@code true} si au moins une ligne {@code artiste_sources} a été mise à jour
   */
  public boolean ignorerIntervention(final UUID artisteId) {
    final int nb =
        jdbc.update(
            """
                UPDATE artiste_sources
                SET necessite_intervention_le = NULL, nom_tm_ignore = nom_tm_incoherent
                WHERE artiste_id = CAST(:id AS uuid)
                """,
            Map.of("id", artisteId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "intervention ignorée artiste=" + artisteId,
          Map.of("artisteId", artisteId.toString()));
    }
    return nb > 0;
  }

  /**
   * Corrige directement l'identifiant Ticketmaster d'un artiste — alternative à la fusion pour un
   * simple mauvais lien (attractionId erroné plutôt qu'un doublon de référentiel). Réinitialise le
   * compteur de santé et efface {@code necessite_intervention_le} : un admin qui corrige
   * manuellement l'identifiant confirme lui-même la nouvelle identité, il n'y a rien de plus à
   * revérifier. Un conflit sur la PK {@code (source, identifiant_externe)} (identifiant déjà
   * attribué à un autre artiste) remonte {@code DuplicateKeyException} vers l'appelant, traduite
   * en 409 par {@code GlobalExceptionHandler}.
   *
   * @param artisteId UUID de l'artiste
   * @param identifiantExterne nouvel attractionId Ticketmaster (non vide)
   * @return {@code true} si l'artiste a une source {@code ticketmaster} et a été mis à jour
   */
  public boolean corrigerIdentifiantTm(final UUID artisteId, final String identifiantExterne) {
    final int nb =
        jdbc.update(
            """
                UPDATE artiste_sources
                SET identifiant_externe = :identifiant, nb_zero_consecutifs = 0,
                    suspect_depuis = NULL, necessite_intervention_le = NULL
                WHERE artiste_id = CAST(:id AS uuid) AND source = 'ticketmaster'
                """,
            Map.of("id", artisteId.toString(), "identifiant", identifiantExterne.strip()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "identifiant TM corrigé artiste=" + artisteId,
          Map.of("artisteId", artisteId.toString(), "identifiantExterne", identifiantExterne));
    }
    return nb > 0;
  }

  /**
   * Modifie le nom affiché d'un artiste (override manuel prioritaire sur l'enrichissement auto).
   *
   * @param artisteId UUID de l'artiste
   * @param nomAffiche nouveau nom affiché, ou null pour revenir à l'enrichissement automatique
   * @return {@code true} si l'artiste existe et a été mis à jour
   */
  public boolean modifierNomAffiche(final UUID artisteId, final String nomAffiche) {
    final String valeur = nomAffiche == null || nomAffiche.isBlank() ? null : nomAffiche.strip();
    final Map<String, Object> params = new HashMap<>();
    params.put("nomAffiche", valeur);
    params.put("id", artisteId.toString());
    final int nb =
        jdbc.update(
            "UPDATE artistes SET nom_affiche = :nomAffiche WHERE id = CAST(:id AS uuid)", params);
    return nb > 0;
  }

  /**
   * Fusionne deux artistes : l'artiste source est absorbé par l'artiste cible (concerts,
   * abonnements, sources externes réassignés), puis supprimé.
   *
   * @param cibleId UUID de l'artiste à conserver
   * @param sourceId UUID de l'artiste à absorber
   * @return {@code true} si la fusion a été effectuée, {@code false} si un UUID est inconnu
   */
  @Transactional
  public boolean fusionner(final UUID cibleId, final UUID sourceId) {
    if (cibleId.equals(sourceId)) {
      return false;
    }
    final Long nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM artistes WHERE id IN (CAST(:cible AS uuid), CAST(:source AS"
                + " uuid))",
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()),
            Long.class);
    if (nbExistants == null || nbExistants < 2) {
      return false;
    }

    final Map<String, Object> ids = Map.of("cible", cibleId.toString(), "source", sourceId.toString());

    // 1. Événements en conflit (même salle/date/heure déjà présent côté cible) : leurs
    // observations sont reportées sur l'événement cible plutôt que perdues (#1192) — celles qui
    // entreraient en conflit sur (evenement_id, source, url) sont supprimées, déjà représentées
    // côté cible. L'événement source, vidé de ses observations, est ensuite supprimé.
    jdbc.update(
        """
        DELETE FROM observations o1
        USING observations o2, evenements e1, evenements e2
        WHERE o1.evenement_id = e1.id AND o2.evenement_id = e2.id
          AND e1.artiste_id = CAST(:source AS uuid) AND e2.artiste_id = CAST(:cible AS uuid)
          AND e1.salle_id = e2.salle_id AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
          AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url
        """,
        ids);
    jdbc.update(
        """
        UPDATE observations SET evenement_id = e2.id
        FROM evenements e1, evenements e2
        WHERE observations.evenement_id = e1.id
          AND e1.artiste_id = CAST(:source AS uuid) AND e2.artiste_id = CAST(:cible AS uuid)
          AND e1.salle_id = e2.salle_id AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
        """,
        ids);
    jdbc.update(
        """
        DELETE FROM evenements
        WHERE artiste_id = CAST(:source AS uuid)
          AND NOT EXISTS (SELECT 1 FROM observations WHERE observations.evenement_id = evenements.id)
        """,
        ids);

    // 2. Réassigner les événements restants (ceux sans doublon côté cible)
    jdbc.update(
        "UPDATE evenements SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id = CAST(:source"
            + " AS uuid)",
        ids);

    jdbc.update(
        """
        DELETE FROM user_artistes
        WHERE artiste_id = CAST(:source AS uuid)
          AND EXISTS (
            SELECT 1 FROM user_artistes u2
            WHERE u2.user_id = user_artistes.user_id AND u2.artiste_id = CAST(:cible AS uuid)
          )
        """,
        ids);
    jdbc.update(
        "UPDATE user_artistes SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id = CAST(:source"
            + " AS uuid)",
        ids);

    // Dédoublonnage préalable des conflits par type de source (sans lui, deux
    // identifiants du même type, ex. deux ticketmaster, coexistent indéfiniment sur l'artiste
    // fusionné).
    dedupliquerArtisteSourcesAvantFusion(cibleId, sourceId);
    jdbc.update(
        "UPDATE artiste_sources SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id ="
            + " CAST(:source AS uuid)",
        ids);

    // une fusion admin est elle-même une résolution manuelle — efface le signal
    // d'intervention hérité de l'artiste absorbé ou déjà posé sur la cible.
    jdbc.update(
        "UPDATE artiste_sources SET necessite_intervention_le = NULL WHERE artiste_id ="
            + " CAST(:cible AS uuid)",
        ids);

    jdbc.update(
        """
        UPDATE artistes SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM artistes WHERE id = CAST(:source AS uuid))),
          deezer_id   = COALESCE(deezer_id, (SELECT deezer_id FROM artistes WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM artistes WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    jdbc.update("DELETE FROM artistes WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        "info", "admin", "fusion artiste cible=" + cibleId + " source=" + sourceId);
    return true;
  }

  /**
   * Supprime, avant réassignation, les doublons d'{@code artiste_sources} qu'une fusion créerait
   * sinon silencieusement : la PK {@code (source, identifiant_externe)} est indépendante de {@code
   * artiste_id}, donc deux lignes portant le même {@code source} (ex. deux identifiants {@code
   * ticketmaster} différents) peuvent coexister indéfiniment sur l'artiste fusionné sans jamais
   * entrer en conflit de PK. Garde la ligne la plus saine (non suspecte prioritaire, puis {@code
   * nb_zero_consecutifs} le plus bas) et supprime l'autre.
   *
   * @param cibleId UUID de l'artiste à conserver
   * @param sourceId UUID de l'artiste à absorber
   */
  private void dedupliquerArtisteSourcesAvantFusion(final UUID cibleId, final UUID sourceId) {
    final List<Map<String, Object>> conflits =
        jdbc.queryForList(
            """
                SELECT src.source AS source,
                       src.identifiant_externe AS id_source,
                       dst.identifiant_externe AS id_cible,
                       src.nb_zero_consecutifs AS sante_source,
                       dst.nb_zero_consecutifs AS sante_cible,
                       (src.suspect_depuis IS NOT NULL) AS suspect_source,
                       (dst.suspect_depuis IS NOT NULL) AS suspect_cible
                FROM artiste_sources src
                JOIN artiste_sources dst ON dst.source = src.source AND dst.artiste_id = CAST(:cible AS uuid)
                WHERE src.artiste_id = CAST(:source AS uuid)
                """,
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()));

    for (final Map<String, Object> conflit : conflits) {
      final boolean garderSource = FusionSourcesUtils.laSourceEstPlusSaine(conflit);
      final Map<String, Object> paramsSuppression = new HashMap<>();
      paramsSuppression.put("source", conflit.get("source"));
      paramsSuppression.put("artisteId", garderSource ? cibleId.toString() : sourceId.toString());
      paramsSuppression.put(
          "identifiantExterne", conflit.get(garderSource ? "id_cible" : "id_source"));
      jdbc.update(
          "DELETE FROM artiste_sources WHERE artiste_id = CAST(:artisteId AS uuid) AND source ="
              + " :source AND identifiant_externe = :identifiantExterne",
          paramsSuppression);
    }
  }
}
