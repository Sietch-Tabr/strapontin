/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Point d'entrée unique pour les nouveaux signalements de matching
 * (détail : CLAUDE.md, Modèle événement canonique).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SignalementMatchingService {

  private static final Logger LOG = LoggerFactory.getLogger(SignalementMatchingService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des détails en JSON
   */
  public SignalementMatchingService(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
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
    try {
      final String detailsJson = details != null ? objectMapper.writeValueAsString(details) : null;
      jdbc.update(
          """
          INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)
          VALUES (:type, :entiteType, CAST(:entiteId AS uuid), :confiance, CAST(:details AS jsonb))
          ON CONFLICT DO NOTHING
          """,
          new MapSqlParameterSource()
              .addValue("type", type)
              .addValue("entiteType", entiteType)
              .addValue("entiteId", entiteId.toString())
              .addValue("confiance", confiance)
              .addValue("details", detailsJson));
    } catch (JsonProcessingException e) {
      LOG.warn("Erreur sérialisation details signalement type={} : {}", type, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Erreur insertion signalement type={} : {}", type, e.getMessage());
    }
  }

  /**
   * Clôt les signalements actifs d'un type dont l'entité ne présente plus l'anomalie — un
   * contrôle périodique se referme ainsi de lui-même une fois la donnée corrigée.
   * @param type type de signalement
   * @param entitesEncoreEnAnomalie entités toujours concernées au dernier contrôle
   * @return nombre de signalements clos
   */
  public int clore(final String type, final Collection<UUID> entitesEncoreEnAnomalie) {
    return jdbc.update(
        """
        UPDATE signalements_matching SET resolu_le = now()
        WHERE type = :type AND resolu_le IS NULL
          AND NOT (entite_id = ANY(CAST(:encore AS uuid[])))
        """,
        new MapSqlParameterSource()
            .addValue("type", type)
            .addValue(
                "encore",
                entitesEncoreEnAnomalie.stream().map(UUID::toString).toArray(String[]::new)));
  }
}
