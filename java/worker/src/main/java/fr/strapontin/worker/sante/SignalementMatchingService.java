/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Point d'entrée unique pour les nouveaux signalements de matching (#1193) — table
 * {@code signalements_matching}, complémentaire aux mécanismes déjà établis
 * ({@code necessite_intervention_le}, {@code contamination_signalee}, {@code doublon_signale}) plutôt qu'un
 * remplacement.
 *
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SignalementMatchingService {

  private static final Logger log = LoggerFactory.getLogger(SignalementMatchingService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SignalementMatchingService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Signale une incohérence de matching, sans effet si un signalement actif (non résolu) du même
   * type existe déjà pour cette entité — même débounce que {@code coherence_wikidata_signalee}.
   *
   * @param type type de signalement (ex. {@code "collision_wikidata_artiste"})
   * @param entiteType {@code "salle"} ou {@code "artiste"}
   * @param entiteId identifiant de l'entité concernée
   * @param confiance niveau de confiance du signalement (ex. {@code "haute"}, {@code "basse"})
   * @param details métadonnées structurées, peut être {@code null}
   */
  public void signaler(
      String type,
      String entiteType,
      UUID entiteId,
      String confiance,
      Map<String, Object> details) {
    try {
      String detailsJson = details != null ? objectMapper.writeValueAsString(details) : null;
      jdbc.update(
          """
          INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)
          VALUES (:type, :entiteType, CAST(:entiteId AS uuid), :confiance, CAST(:details AS jsonb))
          ON CONFLICT DO NOTHING
          """,
          new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
              .addValue("type", type)
              .addValue("entiteType", entiteType)
              .addValue("entiteId", entiteId.toString())
              .addValue("confiance", confiance)
              .addValue("details", detailsJson));
    } catch (JsonProcessingException e) {
      log.warn("Erreur sérialisation details signalement type={} : {}", type, e.getMessage());
    } catch (Exception e) {
      log.warn("Erreur insertion signalement type={} : {}", type, e.getMessage());
    }
  }
}
