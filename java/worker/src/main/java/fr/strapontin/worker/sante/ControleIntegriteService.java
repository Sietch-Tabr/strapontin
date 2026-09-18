/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.SalleHierarchie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Contrôles d'intégrité généralistes du référentiel, journalisation
 * seule, jamais de correction automatique (détail : CLAUDE.md,
 * Modèle événement canonique).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class ControleIntegriteService {

  private static final Logger LOG = LoggerFactory.getLogger(ControleIntegriteService.class);

  /**
   * Signale un événement futur sans {@code salle_id} ni {@code artiste_id}
   * avant que {@code WorkerPurgeService.executerPurgerConcertsHorsPortee}
   * (seuil 30 jours) ne le suggère à la suppression — un signal précoce
   * distinct d'une action déjà en cours.
   */
  private static final int SEUIL_JOURS_EVENEMENT_ORPHELIN = 14;

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param signalementMatchingService journalisation des invariants violés
   */
  public ControleIntegriteService(
      final NamedParameterJdbcTemplate jdbc,
      final SignalementMatchingService signalementMatchingService) {
    this.jdbc = jdbc;
    this.signalementMatchingService = signalementMatchingService;
  }

  /**
   * Exécute les 4 invariants et retourne le nombre total de signalements
   * nouvellement posés.
   *
   * @return nombre de signalements nouvellement posés, tous invariants
   *     confondus
   */
  public int verifier() {
    int signales = 0;
    signales += verifierCyclesSalleParent();
    signales += verifierCoherenceDeptSalle();
    signales += verifierEvenementsOrphelinsAnciens();
    signales += verifierSourcesOrphelines();
    LOG.info("controle:integrite signales={}", signales);
    return signales;
  }

  /**
   * Réutilise {@link SalleHierarchie#creeraitCycle} avec le lien de parenté
   * réel déjà en base (plutôt qu'un candidat hypothétique) pour détecter un
   * cycle déjà présent dans les données.
   */
  private int verifierCyclesSalleParent() {
    final Map<UUID, UUID> parentParSalle = new HashMap<>();
    for (final Map<String, Object> ligne :
        jdbc.queryForList("SELECT id, salle_parent_id FROM salles", Map.of())) {
      parentParSalle.put((UUID) ligne.get("id"), (UUID) ligne.get("salle_parent_id"));
    }

    int signalees = 0;
    for (final Map.Entry<UUID, UUID> entree : parentParSalle.entrySet()) {
      final UUID salleId = entree.getKey();
      final UUID parentId = entree.getValue();
      if (parentId == null) {
        continue;
      }
      if (SalleHierarchie.creeraitCycle(parentId, salleId, parentParSalle::get)) {
        signalementMatchingService.signaler(
            "cycle_salle_parent",
            "salle",
            salleId,
            "haute",
            Map.of("salleParentId", parentId.toString()));
        signalees++;
      }
    }
    return signalees;
  }

  /**
   * Compare {@code salles.dept} au département majoritaire des événements qui
   * lui sont rattachés — une divergence signe soit un {@code dept} obsolète
   * (déménagement jamais répercuté), soit un mauvais rattachement de concerts à
   * cette salle.
   */
  private int verifierCoherenceDeptSalle() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT salle_id, dept_salle FROM (
              SELECT s.id AS salle_id, s.dept AS dept_salle, e.dept AS dept_majoritaire,
                     ROW_NUMBER() OVER (PARTITION BY s.id ORDER BY COUNT(*) DESC) AS rang
              FROM salles s
              JOIN evenements e ON e.salle_id = s.id AND e.dept IS NOT NULL
              WHERE s.dept IS NOT NULL
              GROUP BY s.id, s.dept, e.dept
            ) t
            WHERE rang = 1 AND dept_salle != dept_majoritaire
            """,
            Map.of());

    int signalees = 0;
    for (final Map<String, Object> ligne : lignes) {
      final UUID salleId = (UUID) ligne.get("salle_id");
      signalementMatchingService.signaler(
          "incoherence_dept_salle",
          "salle",
          salleId,
          "basse",
          Map.of("deptSalle", ligne.get("dept_salle")));
      signalees++;
    }
    return signalees;
  }

  private int verifierEvenementsOrphelinsAnciens() {
    final List<UUID> ids =
        jdbc.query(
            """
            SELECT id FROM evenements
            WHERE salle_id IS NULL AND artiste_id IS NULL AND date >= CURRENT_DATE
              AND updated_at < now() - make_interval(days => :seuilJours)
              AND suppression_suggeree_le IS NULL
            """,
            Map.of("seuilJours", SEUIL_JOURS_EVENEMENT_ORPHELIN),
            (rs, _) -> rs.getObject("id", UUID.class));

    for (final UUID id : ids) {
      signalementMatchingService.signaler(
          "evenement_orphelin_ancien", "evenement", id, "basse", null);
    }
    return ids.size();
  }

  /**
   * Une source référençant une entité sans le moindre événement associé (jamais
   * un seul, pas seulement une absence récente) signe un mapping erroné ou un
   * identifiant mort dès l'origine.
   */
  private int verifierSourcesOrphelines() {
    int signalees = 0;
    signalees += verifierSourcesOrphelines(TypeEntiteSource.ARTISTE);
    signalees += verifierSourcesOrphelines(TypeEntiteSource.SALLE);
    return signalees;
  }

  // TypeEntiteSource exclut structurellement tout appel avec un couple
  // table/colonne non constant — seule la requête SQL elle-même varie selon le
  // type, jamais construite par concaténation d'une valeur passée en paramètre.
  private int verifierSourcesOrphelines(final TypeEntiteSource type) {
    final String sql =
        switch (type) {
          case ARTISTE ->
              "SELECT artiste_id AS entite_id, source, identifiant_externe FROM artiste_sources s"
                  + " WHERE NOT EXISTS (SELECT 1 FROM evenements e WHERE e.artiste_id ="
                  + " s.artiste_id)";
          case SALLE ->
              "SELECT salle_id AS entite_id, source, identifiant_externe FROM salle_sources s"
                  + " WHERE NOT EXISTS (SELECT 1 FROM evenements e WHERE e.salle_id = s.salle_id)";
        };
    final String entiteType = type == TypeEntiteSource.ARTISTE ? "artiste" : "salle";

    final List<Map<String, Object>> lignes = jdbc.queryForList(sql, Map.of());
    int signalees = 0;
    for (final Map<String, Object> ligne : lignes) {
      final UUID entiteId = (UUID) ligne.get("entite_id");
      signalementMatchingService.signaler(
          "source_orpheline",
          entiteType,
          entiteId,
          "basse",
          Map.of(
              "source", ligne.get("source"),
              "identifiantExterne", ligne.get("identifiant_externe")));
      signalees++;
    }
    return signalees;
  }
}
