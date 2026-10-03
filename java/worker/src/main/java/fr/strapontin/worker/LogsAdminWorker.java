/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
   * Insère une entrée dans {@code logs_admin}, sans jamais faire échouer le
   * job ; un avertissement identique écrit depuis moins de 24 h est ignoré.
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
      // Un avertissement identique déjà écrit dans les dernières 24 h n'apprend
      // rien de plus : répété à chaque cycle, il noyait le journal.
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          SELECT :ts, :niveau, :source, :message, CAST(:meta AS jsonb)
          WHERE :niveau <> 'warn' OR NOT EXISTS (
              SELECT 1 FROM logs_admin
              WHERE niveau = 'warn' AND source = :source AND message = :message
                AND ts > now() - interval '24 hours')
          """,
          Map.of(
              "ts", Timestamp.from(Instant.now()),
              "niveau", niveau,
              "source", source,
              "message", message,
              "meta", metaJson));
    } catch (JacksonException e) {
      LOG.warn("Erreur sérialisation meta log admin source={} : {}", source, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Erreur insertion log admin source={} : {}", source, e.getMessage());
    }
  }
}
