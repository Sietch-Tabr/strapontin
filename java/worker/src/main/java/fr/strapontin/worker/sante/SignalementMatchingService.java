/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.worker.LogsAdminWorker;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Point d'entrée unique pour les nouveaux signalements de matching
 * (détail : CLAUDE.md, Modèle événement canonique).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SignalementMatchingService {

  private static final Logger LOG = LoggerFactory.getLogger(SignalementMatchingService.class);

  // Type -> clé des détails qui identifie ce que l'administrateur a tranché.
  private static final Map<String, String> CLES_DECISION_CONSERVEE =
      Map.of(
          "creneau_double_meme_source", "jumeau",
          "produit_eventim_double", "jumeau",
          "seance_dans_deux_salles", "jumeau",
          "wikidata_coordonnees_divergentes", "wikidataId");

  // Au-delà, un contrôle est en régression plutôt qu'à traiter.
  private static final int SEUIL_VOLUME_ANORMAL = 200;
  private static final String CLE_TYPE = "type";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des détails en JSON
   * @param logsAdminWorker avertissement quand un type de signalement déborde
   */
  public SignalementMatchingService(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Signale une incohérence (détail : CLAUDE.md, Modèle événement canonique).
   * @param type type de signalement (ex. {@code "collision_wikidata_artiste"})
   * @param entiteType {@code "salle"} ou {@code "artiste"}
   * @param entiteId identifiant de l'entité concernée
   * @param confiance niveau de confiance (ex. {@code "haute"}, {@code "basse"})
   * @param details métadonnées structurées, peut être {@code null}
   */
  public void signaler(
      final String type,
      final String entiteType,
      final UUID entiteId,
      final String confiance,
      final Map<String, Object> details) {
    if (decisionDejaPrise(type, entiteId, details)) {
      return;
    }
    try {
      final String detailsJson = details != null ? objectMapper.writeValueAsString(details) : null;
      jdbc.update(
          """
          INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)
          VALUES (:type, :entiteType, CAST(:entiteId AS uuid), :confiance, CAST(:details AS jsonb))
          ON CONFLICT DO NOTHING
          """,
          new MapSqlParameterSource()
              .addValue(CLE_TYPE, type)
              .addValue("entiteType", entiteType)
              .addValue("entiteId", entiteId.toString())
              .addValue("confiance", confiance)
              .addValue("details", detailsJson));
    } catch (JacksonException e) {
      LOG.warn("Erreur sérialisation details signalement type={} : {}", type, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Erreur insertion signalement type={} : {}", type, e.getMessage());
    }
  }

  // Une paire ou un candidat que l'administrateur a déjà tranché n'est plus signalé
  // tant qu'il reste le même ; une clôture automatique ne vaut pas décision.
  private boolean decisionDejaPrise(
      final String type, final UUID entiteId, final Map<String, Object> details) {
    final String cle = CLES_DECISION_CONSERVEE.get(type);
    if (cle == null || details == null || details.get(cle) == null) {
      return false;
    }
    final Long nombre =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM signalements_matching
            WHERE type = :type AND entite_id = CAST(:entiteId AS uuid)
              AND resolu_manuellement AND details ->> :cle = :valeur
            """,
            new MapSqlParameterSource()
                .addValue(CLE_TYPE, type)
                .addValue("entiteId", entiteId.toString())
                .addValue("cle", cle)
                .addValue("valeur", String.valueOf(details.get(cle))),
            Long.class);
    return nombre != null && nombre > 0;
  }

  /**
   * Clôt le signalement actif d'une seule entité, quand un contrôle qui ne
   * balaie qu'un lot par nuit constate que l'anomalie a disparu.
   * @param type type de signalement
   * @param entiteId entité qui n'est plus en anomalie
   * @return nombre de signalements clos
   */
  public int cloreEntite(final String type, final UUID entiteId) {
    return jdbc.update(
        """
        UPDATE signalements_matching SET resolu_le = now()
        WHERE type = :type AND resolu_le IS NULL AND entite_id = CAST(:entiteId AS uuid)
        """,
        new MapSqlParameterSource()
            .addValue(CLE_TYPE, type)
            .addValue("entiteId", entiteId.toString()));
  }

  /**
   * Clôt les signalements actifs d'un type dont l'entité n'est plus en
   * anomalie : un contrôle périodique se referme de lui-même.
   * @param entitesEncoreEnAnomalie entités toujours concernées
   * @return nombre de signalements clos
   */
  public int clore(final String type, final Collection<UUID> entitesEncoreEnAnomalie) {
    if (entitesEncoreEnAnomalie.size() > SEUIL_VOLUME_ANORMAL) {
      logsAdminWorker.inserer(
          "warn",
          "signalements",
          "Volume anormal de signalements « "
              + type
              + " » : "
              + entitesEncoreEnAnomalie.size()
              + " en un passage",
          Map.of(CLE_TYPE, type, "nombre", entitesEncoreEnAnomalie.size()));
    }
    return jdbc.update(
        """
        UPDATE signalements_matching SET resolu_le = now()
        WHERE type = :type AND resolu_le IS NULL
          AND NOT (entite_id = ANY(CAST(:encore AS uuid[])))
        """,
        new MapSqlParameterSource()
            .addValue(CLE_TYPE, type)
            .addValue(
                "encore",
                entitesEncoreEnAnomalie.stream().map(UUID::toString).toArray(String[]::new)));
  }
}
