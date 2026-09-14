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

/**
 * Écriture dans {@code logs_admin} depuis le worker — le module n'a pas
 * accès au {@code LogAdminService} du module web (deux applications
 * Spring Boot séparées, ports 8080/8081).
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class LogsAdminWorker {

  private static final Logger LOG = LoggerFactory.getLogger(LogsAdminWorker.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des métadonnées en JSON
   */
  public LogsAdminWorker(final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Insère une entrée dans {@code logs_admin} — échoue silencieusement
   * (log SLF4J local), un log manqué ne doit jamais faire échouer le job.
   * @param niveau "info", "warn" ou "error"
   * @param source source technique (ex. "reconciliation-nocturne")
   * @param message message lisible
   * @param meta métadonnées structurées, peut être null
   */
  public void inserer(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    try {
      final String metaJson = meta != null ? objectMapper.writeValueAsString(meta) : "{}";
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
      LOG.warn("Erreur sérialisation meta log admin source={} : {}", source, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Erreur insertion log admin source={} : {}", source, e.getMessage());
    }
  }
}
