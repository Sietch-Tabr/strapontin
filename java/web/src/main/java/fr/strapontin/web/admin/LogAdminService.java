/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de journalisation admin (portage de {@code log-admin.ts}).
///
/// <p>Remplace la liste Redis {@code strapontin:admin:logs} par la table PostgreSQL
/// {@code logs_admin}. Les 200 entrées les plus récentes sont conservées.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class LogAdminService {

  private static final Logger log = LoggerFactory.getLogger(LogAdminService.class);
  private static final int NB_LOGS_MAX = 200;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;

  public LogAdminService(NamedParameterJdbcTemplate jdbc, ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.jackson = jackson;
  }

  /// Entrée de log admin.
  ///
  /// @param ts       horodatage Unix en millisecondes
  /// @param niveau   niveau de log ({@code debug}, {@code info}, {@code warn}, {@code error})
  /// @param source   module source
  /// @param message  message
  /// @param meta     métadonnées JSON brut (peut être nul)
  public record EntreeLogAdmin(long ts, String niveau, String source, String message, String meta) {}

  /// Journalise un événement admin sans métadonnées.
  ///
  /// @param niveau  niveau de log
  /// @param source  module source
  /// @param message message
  public void logAdmin(String niveau, String source, String message) {
    logAdmin(niveau, source, message, null);
  }

  /// Journalise un événement admin avec métadonnées.
  ///
  /// @param niveau  niveau de log
  /// @param source  module source
  /// @param message message
  /// @param meta    métadonnées (sérialisées en JSON), ou {@code null}
  public void logAdmin(String niveau, String source, String message, Map<String, Object> meta) {
    try {
      PGobject pgMeta = new PGobject();
      pgMeta.setType("jsonb");
      pgMeta.setValue(meta != null ? jackson.writeValueAsString(meta) : null);

      Map<String, Object> params = new HashMap<>();
      params.put("ts", Instant.now().toEpochMilli());
      params.put("niveau", niveau);
      params.put("source", source);
      params.put("message", message);
      params.put("meta", pgMeta);

      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, :meta)
          """,
          params);

      jdbc.update(
          """
          DELETE FROM logs_admin WHERE id NOT IN (
            SELECT id FROM logs_admin ORDER BY ts DESC LIMIT :max
          )
          """,
          Map.of("max", NB_LOGS_MAX));

    } catch (JsonProcessingException e) {
      log.warn("Erreur sérialisation meta log admin", e);
    } catch (Exception e) {
      log.warn("Erreur insertion log admin", e);
    }
  }

  /// Retourne les {@code limite} entrées de log les plus récentes.
  ///
  /// @param limite nombre maximum d'entrées à retourner
  /// @return la liste des entrées, de la plus récente à la plus ancienne
  public List<EntreeLogAdmin> lireLogs(int limite) {
    return jdbc.query(
        """
        SELECT ts, niveau, source, message, meta::text
        FROM logs_admin
        ORDER BY ts DESC
        LIMIT :limite
        """,
        Map.of("limite", limite),
        (rs, _) ->
            new EntreeLogAdmin(
                rs.getLong("ts"),
                rs.getString("niveau"),
                rs.getString("source"),
                rs.getString("message"),
                rs.getString("meta")));
  }
}
