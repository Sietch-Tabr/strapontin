/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Listener Quartz global : alimente {@code logs_admin} à chaque
 * démarrage et fin de job, pour observer en temps réel depuis
 * l'admin, au même titre que les actions manuelles.
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class LogAdminJobListener implements JobListener {

  private static final Logger LOG = LoggerFactory.getLogger(LogAdminJobListener.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;

  /**
   * @param jdbc accès JDBC
   * @param jackson sérialisation des métadonnées de job en JSON
   */
  public LogAdminJobListener(final NamedParameterJdbcTemplate jdbc, final ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.jackson = jackson;
  }

  @Override
  public String getName() {
    return "logAdmin";
  }

  @Override
  public void jobToBeExecuted(final JobExecutionContext context) {
    insererLog("info", "Job démarré : " + nomJob(context), null);
  }

  @Override
  public void jobExecutionVetoed(final JobExecutionContext context) {
    // job annulé par un trigger listener — aucune action requise
  }

  @Override
  public void jobWasExecuted(final JobExecutionContext context, final JobExecutionException ex) {
    final String nom = nomJob(context);
    final long dureeMs = context.getJobRunTime();
    if (ex != null) {
      final Map<String, Object> meta = new HashMap<>();
      meta.put("dureeMs", dureeMs);
      meta.put("erreur", ex.getMessage() != null ? ex.getMessage() : "erreur inconnue");
      insererLog("error", "Job échoué : " + nom, meta);
    } else {
      insererLog("info", "Job terminé : " + nom, Map.of("dureeMs", dureeMs));
    }
  }

  private String nomJob(final JobExecutionContext context) {
    final String cle = context.getJobDetail().getKey().getName();
    return NomsJobs.PAR_CLE_QUARTZ.getOrDefault(cle, cle);
  }

  private void insererLog(
      final String niveau, final String message, final Map<String, Object> meta) {
    try {
      final String metaJson = meta != null ? jackson.writeValueAsString(meta) : null;
      final Map<String, Object> params = new HashMap<>();
      params.put("ts", Timestamp.from(Instant.now()));
      params.put("niveau", niveau);
      params.put("source", "worker");
      params.put("message", message);
      params.put("meta", metaJson);
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          params);
    } catch (JsonProcessingException e) {
      LOG.warn("Erreur sérialisation meta log admin : {}", e.getMessage());
    } catch (Exception e) {
      LOG.warn("Impossible d'insérer log admin : {}", e.getMessage());
    }
  }
}
