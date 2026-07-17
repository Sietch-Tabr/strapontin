/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Écriture dans `logs_admin` depuis le worker (#706/#711/#716).
///
/// Le worker n'a pas accès au `LogAdminService` du module web (deux applications Spring Boot
/// séparées, ports 8080/8081). Point d'insertion générique utilisé par le bilan de panne
/// mutualisé de {@code WorkerStatsService#remonterBilan} et {@code NotificationDigestService}
/// (#909/#912) — plusieurs services `worker.Worker*Service` écrivent encore directement en SQL
/// pour certains cas spécifiques non couverts par cette abstraction (ex.
/// {@code WorkerRevocationService#executerRevoquerAdmins}).
///
/// @since 2.6.0
/// @author Kwaite
@Service
public class LogsAdminWorker {

  private static final Logger log = LoggerFactory.getLogger(LogsAdminWorker.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public LogsAdminWorker(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /// Insère une entrée dans `logs_admin`. Échoue silencieusement (log SLF4J local) — un log
  /// admin manqué ne doit jamais faire échouer le job appelant.
  ///
  /// @param niveau  {@code "info"}, {@code "warn"} ou {@code "error"}
  /// @param source  source technique (ex. {@code "reconciliation-nocturne"})
  /// @param message message lisible
  /// @param meta    métadonnées structurées, peut être null
  public void inserer(String niveau, String source, String message, Map<String, Object> meta) {
    try {
      String metaJson = meta != null ? objectMapper.writeValueAsString(meta) : "{}";
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          Map.of(
              "ts", System.currentTimeMillis(),
              "niveau", niveau,
              "source", source,
              "message", message,
              "meta", metaJson));
    } catch (JsonProcessingException e) {
      log.warn("Erreur sérialisation meta log admin source={} : {}", source, e.getMessage());
    } catch (Exception e) {
      log.warn("Erreur insertion log admin source={} : {}", source, e.getMessage());
    }
  }
}
