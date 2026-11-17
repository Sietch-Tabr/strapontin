/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.JournalAdmin;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
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

  private final JournalAdmin journal;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des métadonnées en JSON
   */
  public LogsAdminWorker(final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.journal = new JournalAdmin(jdbc, objectMapper);
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
    journal.ecrireSansRepetition(niveau, source, message, meta == null ? Map.of() : meta);
  }
}
