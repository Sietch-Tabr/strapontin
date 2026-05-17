/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/// Listener Quartz global : alimente {@code logs_admin} à chaque démarrage et fin de job.
///
/// <p>Permet d'observer en temps réel les exécutions Quartz depuis la page d'administration,
/// au même titre que les actions manuelles de l'interface web.
///
/// @since 2.0.0
/// @author Kwaite
@Component
public class LogAdminJobListener implements JobListener {

  private static final Logger log = LoggerFactory.getLogger(LogAdminJobListener.class);
  private static final int NB_LOGS_MAX = 200;

  private static final Map<String, String> NOM_JOBS =
      Map.of(
          "fetchArtistesBatch", "fetch:artistes:batch",
          "fetchRegion", "fetch:region",
          "purgerConcerts", "purger:concerts",
          "recalculerMatches", "recalculer:matches",
          "notifierDigest", "notifier:digest");

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;

  public LogAdminJobListener(NamedParameterJdbcTemplate jdbc, ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.jackson = jackson;
  }

  @Override
  public String getName() {
    return "logAdmin";
  }

  @Override
  public void jobToBeExecuted(JobExecutionContext context) {
    insererLog("info", "Job démarré : " + nomJob(context), null);
  }

  @Override
  public void jobExecutionVetoed(JobExecutionContext context) {}

  @Override
  public void jobWasExecuted(JobExecutionContext context, JobExecutionException ex) {
    String nom = nomJob(context);
    long dureeMs = context.getJobRunTime();
    if (ex != null) {
      Map<String, Object> meta = new HashMap<>();
      meta.put("dureeMs", dureeMs);
      meta.put("erreur", ex.getMessage() != null ? ex.getMessage() : "erreur inconnue");
      insererLog("error", "Job échoué : " + nom, meta);
    } else {
      insererLog("info", "Job terminé : " + nom, Map.of("dureeMs", dureeMs));
    }
  }

  private String nomJob(JobExecutionContext context) {
    String cle = context.getJobDetail().getKey().getName();
    return NOM_JOBS.getOrDefault(cle, cle);
  }

  private void insererLog(String niveau, String message, Map<String, Object> meta) {
    try {
      String metaJson = meta != null ? jackson.writeValueAsString(meta) : null;
      Map<String, Object> params = new HashMap<>();
      params.put("ts", Instant.now().toEpochMilli());
      params.put("niveau", niveau);
      params.put("source", "worker");
      params.put("message", message);
      params.put("meta", metaJson);
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, :meta::jsonb)
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
      log.warn("Erreur sérialisation meta log admin : {}", e.getMessage());
    } catch (Exception e) {
      log.warn("Impossible d'insérer log admin : {}", e.getMessage());
    }
  }
}
