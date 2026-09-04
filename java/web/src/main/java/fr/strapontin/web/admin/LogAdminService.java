/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
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
/// <p>Remplace la liste Redis {@code strapontin:admin:logs} par la table PostgreSQL {@code
/// logs_admin}. La rétention est temporelle : la purge des entrées de plus de 30 jours est
/// planifiée côté worker ({@code PurgerLogsAdminJob}).
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class LogAdminService {

  private static final Logger log = LoggerFactory.getLogger(LogAdminService.class);
  private static final int LIMITE_ERREURS_DEPUIS = 1000;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;

  public LogAdminService(NamedParameterJdbcTemplate jdbc, ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.jackson = jackson;
  }

  /// Entrée de log admin.
  ///
  /// @param ts horodatage Unix en millisecondes
  /// @param niveau niveau de log (`debug`, `info`, `warn`, `error`)
  /// @param source module source
  /// @param message message
  /// @param meta métadonnées JSON brut (peut être null)
  public record EntreeLogAdmin(
      long ts, String niveau, String source, String message, String meta) {}

  /// Journalise un événement admin sans métadonnées.
  ///
  /// @param niveau niveau de log
  /// @param source module source
  /// @param message message
  public void logAdmin(String niveau, String source, String message) {
    logAdmin(niveau, source, message, null);
  }

  /// Journalise un événement admin avec métadonnées.
  ///
  /// @param niveau niveau de log
  /// @param source module source
  /// @param message message
  /// @param meta métadonnées (sérialisées en JSON), ou null
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

  /// Retourne les entrées de niveau {@code error} postérieures à {@code tsDepuis}, plafonnées à
  /// {@value #LIMITE_ERREURS_DEPUIS} — un retard important (destinataire d'alerte absent pendant
  /// plusieurs cycles) est ainsi absorbé sur plusieurs digests successifs plutôt que de charger un
  /// lot non borné, le curseur avançant jusqu'au dernier ts retourné sans rien perdre.
  ///
  /// @param tsDepuis horodatage Unix en millisecondes, exclusif
  /// @return les entrées d'erreur, de la plus ancienne à la plus récente
  public List<EntreeLogAdmin> lireErreursDepuis(long tsDepuis) {
    return jdbc.query(
        """
        SELECT ts, niveau, source, message, meta::text
        FROM logs_admin
        WHERE niveau = 'error' AND ts > :tsDepuis
        ORDER BY ts
        LIMIT :limite
        """,
        Map.of("tsDepuis", tsDepuis, "limite", LIMITE_ERREURS_DEPUIS),
        (rs, _) ->
            new EntreeLogAdmin(
                rs.getLong("ts"),
                rs.getString("niveau"),
                rs.getString("source"),
                rs.getString("message"),
                rs.getString("meta")));
  }
}
