/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Écriture dans la table {@code logs_admin}, partagée par le web et le
 * worker : un seul INSERT, avec ou sans suppression des avertissements
 * identiques des dernières 24 heures.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class JournalAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(JournalAdmin.class);

  private static final String INSERTION =
      """
      INSERT INTO logs_admin (ts, niveau, source, message, meta)
      VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
      """;

  /* Un avertissement identique déjà écrit dans les dernières 24 h n'apprend
   * rien de plus : répété à chaque cycle ou sondage, il noyait le journal. */
  private static final String INSERTION_SANS_REPETITION =
      """
      INSERT INTO logs_admin (ts, niveau, source, message, meta)
      SELECT :ts, :niveau, :source, :message, CAST(:meta AS jsonb)
      WHERE :niveau <> 'warn' OR NOT EXISTS (
          SELECT 1 FROM logs_admin
          WHERE niveau = 'warn' AND source = :source AND message = :message
            AND ts > now() - interval '24 hours')
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des métadonnées en JSON
   */
  public JournalAdmin(final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Écrit une entrée sans jamais faire échouer l'appelant.
   *
   * @param niveau "info", "warn" ou "error"
   * @param source source technique
   * @param message message lisible
   * @param meta métadonnées structurées, ou null
   */
  public void ecrire(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    ecrireSansEchouer(INSERTION, niveau, source, message, meta);
  }

  /**
   * Écrit sans faire échouer l'appelant, en ignorant un avertissement
   * identique (source et message) écrit depuis moins de 24 h.
   * @param niveau "info", "warn" ou "error"
   * @param source source technique
   * @param message message lisible
   * @param meta métadonnées structurées, ou null
   */
  public void ecrireSansRepetition(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    ecrireSansEchouer(INSERTION_SANS_REPETITION, niveau, source, message, meta);
  }

  /**
   * Écrit en laissant l'erreur remonter, pour une écriture qui partage
   * le sort de la transaction en cours.
   * @param niveau "info", "warn" ou "error"
   * @param source source technique
   * @param message message lisible
   * @param meta métadonnées structurées, ou null
   */
  public void ecrireDansLaTransaction(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    jdbc.update(INSERTION, parametres(niveau, source, message, meta));
  }

  private void ecrireSansEchouer(
      final String requete,
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    try {
      jdbc.update(requete, parametres(niveau, source, message, meta));
    } catch (final RuntimeException e) {
      LOG.warn("Écriture impossible dans logs_admin source={} : {}", source, e.getMessage());
    }
  }

  private Map<String, Object> parametres(
      final String niveau,
      final String source,
      final String message,
      final Map<String, Object> meta) {
    final Map<String, Object> parametres = new HashMap<>();
    parametres.put("ts", Timestamp.from(Instant.now()));
    parametres.put("niveau", niveau);
    parametres.put("source", source);
    parametres.put("message", message);
    parametres.put("meta", meta == null ? null : objectMapper.writeValueAsString(meta));
    return parametres;
  }
}
