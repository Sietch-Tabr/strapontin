/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Statistiques des jobs Quartz et quota Ticketmaster pour l'onglet admin.
 *
 * <p>Extrait d'{@code AdminService} — {@code AdminService} reste dédié à la seule gestion des
 * comptes utilisateurs.
 *
 * @since 2.1.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class JobsAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(JobsAdminService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final WorkerProxyService workerProxyService;

  /**
   * @param jdbc               accès JDBC
   * @param objectMapper       désérialiseur JSON (quota TM)
   * @param workerProxyService proxy HTTP vers le worker (jobs en cours, prochaines exécutions)
   */
  public JobsAdminService(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final WorkerProxyService workerProxyService) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.workerProxyService = workerProxyService;
  }

  /**
   * Retourne les statistiques des jobs Quartz et le quota TM.
   *
   * @return map contenant statsJobs, quotaTM, etatQuota, workerLastSuccess, jobsEnCours
   */
  public Map<String, Object> lireJobs() {
    final List<StatJob> statsJobs =
        jdbc.query(
            """
                SELECT type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur
                FROM fetch_jobs
                ORDER BY type_job
                """,
            Map.of(),
            (rs, _) ->
                new StatJob(
                    rs.getString("type_job"),
                    rs.getTimestamp("derniere_exec") != null
                        ? rs.getTimestamp("derniere_exec").toInstant()
                        : null,
                    rs.getObject("duree_ms", Integer.class),
                    rs.getObject("nb_inseres", Integer.class),
                    rs.getObject("nb_mis_a_jour", Integer.class),
                    rs.getString("erreur"),
                    null));

    QuotaTM quotaTM = null;
    final List<String> quotaJson =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = 'TM_QUOTA_JSON'",
            Map.of(),
            String.class);
    if (!quotaJson.isEmpty()) {
      try {
        quotaTM = objectMapper.readValue(quotaJson.get(0), QuotaTM.class);
      } catch (Exception _) {
        LOG.warn("Quota TM malformé — ignoré");
      }
    }

    Instant workerLastSuccess = null;
    try {
      final Timestamp ts =
          jdbc.queryForObject(
              "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE (erreur IS NULL OR erreur = '')",
              Map.of(),
              Timestamp.class);
      workerLastSuccess = ts != null ? ts.toInstant() : null;
    } catch (Exception _) {
      LOG.debug("Impossible de lire le dernier job réussi");
    }

    final List<String> jobsEnCours = workerProxyService.obtenirJobsEnCours();
    final Map<String, Instant> prochainesExec = workerProxyService.obtenirProchainesExec();

    final QuotaTM quotaFinal = quotaTM;
    final List<StatJob> statsConnus =
        statsJobs.stream()
            .map(
                j ->
                    new StatJob(
                        j.typeJob(),
                        j.derniereExec(),
                        j.dureeMs(),
                        j.nbInseres(),
                        j.nbMisAJour(),
                        j.erreur(),
                        prochainesExec.get(j.typeJob())))
            .toList();

    final List<StatJob> statsComplets =
        completerAvecJobsJamaisExecutes(statsConnus, prochainesExec);

    String etatQuota = "ok";
    if (quotaFinal != null && quotaFinal.total() > 0) {
      final int pct = quotaFinal.dispo() * 100 / quotaFinal.total();
      etatQuota = pct < 10 ? "critique" : pct < 20 ? "attention" : "ok";
    }

    final Map<String, Object> reponse = new HashMap<>();
    reponse.put("statsJobs", statsComplets);
    reponse.put("quotaTM", quotaFinal);
    reponse.put("etatQuota", etatQuota);
    reponse.put("workerLastSuccess", workerLastSuccess);
    reponse.put("jobsEnCours", jobsEnCours);
    return reponse;
  }

  /**
   * Complète les statistiques de jobs avec une entrée synthétique pour tout job Quartz planifié
   * ({@code prochainesExec}) mais n'ayant jamais écrit de ligne dans {@code fetch_jobs}.
   *
   * <p>Sans cette complétion, un job de maintenance tout juste ajouté (ex. {@code
   * reconciliation:nocturne}, cron 3h) reste invisible et non déclenchable manuellement depuis
   * l'admin tant que son premier passage cron automatique n'a pas eu lieu.
   *
   * @param statsConnus    statistiques déjà présentes en base, triées ou non
   * @param prochainesExec prochaines exécutions planifiées par {@code typeJob} (source de vérité
   *                       du worker sur les jobs existants)
   * @return statistiques triées par {@code typeJob}, complétées des jobs jamais exécutés
   */
  static List<StatJob> completerAvecJobsJamaisExecutes(
      final List<StatJob> statsConnus, final Map<String, Instant> prochainesExec) {
    final Set<String> typesConnus =
        statsConnus.stream().map(StatJob::typeJob).collect(Collectors.toSet());
    final List<StatJob> statsJamaisExecutes =
        prochainesExec.keySet().stream()
            .filter(typeJob -> !typesConnus.contains(typeJob))
            .map(
                typeJob ->
                    new StatJob(typeJob, null, null, null, null, null, prochainesExec.get(typeJob)))
            .toList();

    final List<StatJob> statsComplets = new ArrayList<>(statsConnus);
    statsComplets.addAll(statsJamaisExecutes);
    statsComplets.sort(Comparator.comparing(StatJob::typeJob));
    return statsComplets;
  }
}
