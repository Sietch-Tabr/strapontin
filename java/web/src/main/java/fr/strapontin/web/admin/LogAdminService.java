/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.JournalAdmin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Service de journalisation admin — remplace la liste Redis {@code
 * strapontin:admin:logs} par la table PostgreSQL {@code logs_admin}
 * (rétention temporelle, purge > 30 jours côté worker).
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class LogAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(LogAdminService.class);
  private static final int LIMITE_ERREURS_DEPUIS = 1000;

  private final NamedParameterJdbcTemplate jdbc;
  private final JournalAdmin journal;

  /**
   * @param jdbc accès JDBC
   * @param jackson sérialisation des métadonnées en JSON
   */
  public LogAdminService(final NamedParameterJdbcTemplate jdbc, final ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.journal = new JournalAdmin(jdbc, jackson);
  }

  /**
   * Entrée de log admin : identifiant séquentiel (ordre d'insertion),
   * horodatage en ms, niveau, source, message et métadonnées JSON (null).
   */
  public record EntreeLogAdmin(
      long id, long ts, String niveau, String source, String message, String meta) {}

  /**
   * Journalise un événement admin sans métadonnées.
   *
   * @param niveau niveau de log
   * @param source module source
   * @param message message
   */
  public void logAdmin(final String niveau, final String source, final String message) {
    logAdmin(niveau, source, message, null);
  }

  /**
   * Journalise un événement admin avec métadonnées.
   *
   * @param niveau niveau de log
   * @param source module source
   * @param message message
   * @param meta métadonnées (sérialisées en JSON), ou null
   */
  public void logAdmin(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    journal.ecrire(niveau, source, message, meta);
  }

  /**
   * Journalise un événement admin, sauf si le même avertissement (source et
   * message) a été écrit depuis moins de 24 h.
   * @param niveau niveau de log
   * @param source module source
   * @param message message
   * @param meta métadonnées (sérialisées en JSON), ou null
   */
  public void logAdminSansRepetition(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    journal.ecrireSansRepetition(niveau, source, message, meta);
  }

  /**
   * Retourne les {@code limite} entrées de log les plus récentes.
   *
   * @param limite nombre maximum d'entrées à retourner
   * @return la liste des entrées, de la plus récente à la plus ancienne
   */
  public List<EntreeLogAdmin> lireLogs(final int limite) {
    return jdbc.query(
        """
        SELECT id, ts, niveau, source, message, meta::text
        FROM logs_admin
        ORDER BY ts DESC
        LIMIT :limite
        """,
        Map.of("limite", limite),
        (rs, _) ->
            new EntreeLogAdmin(
                rs.getLong("id"),
                rs.getTimestamp("ts").toInstant().toEpochMilli(),
                rs.getString("niveau"),
                rs.getString("source"),
                rs.getString("message"),
                rs.getString("meta")));
  }

  /**
   * Erreurs d'identifiant supérieur à {@code idDepuis}, plafonnées à
   * {@value #LIMITE_ERREURS_DEPUIS} : l'identifiant départage deux erreurs
   * de la même milliseconde, et un retard se résorbe sur plusieurs digests.
   * @return les entrées d'erreur, dans l'ordre d'insertion
   */
  public List<EntreeLogAdmin> lireErreursDepuis(final long idDepuis) {
    return jdbc.query(
        """
        SELECT id, ts, niveau, source, message, meta::text
        FROM logs_admin
        WHERE niveau = 'error' AND id > :idDepuis
        ORDER BY id
        LIMIT :limite
        """,
        Map.of("idDepuis", idDepuis, "limite", LIMITE_ERREURS_DEPUIS),
        (rs, _) ->
            new EntreeLogAdmin(
                rs.getLong("id"),
                rs.getTimestamp("ts").toInstant().toEpochMilli(),
                rs.getString("niveau"),
                rs.getString("source"),
                rs.getString("message"),
                rs.getString("meta")));
  }

  /**
   * Plus grand identifiant d'entrée dont l'horodatage, tronqué à la
   * milliseconde, ne dépasse pas {@code tsMillis} — conversion d'un ancien
   * curseur temporel en curseur d'identifiant.
   * @param tsMillis horodatage Unix en millisecondes, inclusif
   * @return l'identifiant, ou 0 si aucune entrée
   */
  public long idMaxJusquA(final long tsMillis) {
    final Long id =
        jdbc.queryForObject(
            "SELECT COALESCE(max(id), 0) FROM logs_admin"
                + " WHERE date_trunc('milliseconds', ts) <= :ts",
            Map.of("ts", Timestamp.from(Instant.ofEpochMilli(tsMillis))),
            Long.class);
    return id != null ? id : 0L;
  }

  /**
   * Compte les échecs d'authentification récents d'un identifiant
   * (source {@code auth}, niveau {@code warn}).
   * @param identifiant l'identifiant ciblé par les échecs
   * @param depuisMs horodatage Unix en millisecondes, exclusif
   * @return le nombre d'échecs consignés depuis {@code depuisMs}
   */
  public long compterEchecsConnexionRecents(final String identifiant, final long depuisMs) {
    final Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM logs_admin
            WHERE source = 'auth' AND niveau = 'warn'
              AND meta ->> 'identifiant' = :identifiant AND ts > :depuisMs
            """,
            Map.of(
                "identifiant",
                identifiant,
                "depuisMs",
                Timestamp.from(Instant.ofEpochMilli(depuisMs))),
            Long.class);
    return total != null ? total : 0;
  }
}
