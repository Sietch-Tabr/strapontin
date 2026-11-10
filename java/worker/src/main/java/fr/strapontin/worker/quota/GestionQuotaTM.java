/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.quota;

import fr.strapontin.core.AccesAppConfig;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.worker.LogsAdminWorker;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Gestion du quota journalier Ticketmaster ({@code app_config})
 * (détail : CLAUDE.md, Jobs background).
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class GestionQuotaTM {

  private static final Logger LOG = LoggerFactory.getLogger(GestionQuotaTM.class);

  private static final int SEUIL_QUOTA_CRON = 500;
  private static final int SEUIL_QUOTA_ABORT = 50;
  private static final int SEUIL_QUOTA_WARN = 200;

  private static final String CLE_QUOTA = "TM_QUOTA_JSON";
  private static final String CLE_DISPO = "dispo";
  private static final String CLE_TOTAL = "total";

  private final NamedParameterJdbcTemplate jdbc;
  private final AccesAppConfig acces;
  private final ObjectMapper objectMapper;
  private final LogsAdminWorker logsAdmin;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation du quota en JSON
   * @param logsAdmin journal d'administration
   */
  public GestionQuotaTM(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final LogsAdminWorker logsAdmin) {
    this.jdbc = jdbc;
    this.acces = new AccesAppConfig(jdbc);
    this.objectMapper = objectMapper;
    this.logsAdmin = logsAdmin;
  }

  /**
   * Lit le quota courant depuis {@code app_config} (détail :
   * CLAUDE.md, Jobs background).
   * @return quota en vigueur (renouvelé après la remise à zéro), ou vide si
   *     absent, malformé ou de total inconnu
   */
  public Optional<QuotaTM> lireQuota() {
    try {
      final Optional<String> json = acces.lire(CLE_QUOTA);
      if (json.isEmpty()) {
        return Optional.empty();
      }
      final QuotaTM quota = objectMapper.readValue(json.get(), QuotaTM.class);
      final QuotaTM enVigueur = quota.enVigueur(Instant.now());
      if (enVigueur == null) {
        // Total inconnu : le prochain appel Ticketmaster réécrira le vrai quota.
        acces.supprimer(CLE_QUOTA);
        LOG.info("quota TM inconnu après reset — repris au prochain appel Ticketmaster");
        return Optional.empty();
      }
      if (!enVigueur.equals(quota)) {
        ecrireQuota(enVigueur);
        LOG.info("quota TM renouvelé automatiquement après reset — dispo={}", enVigueur.dispo());
      }
      return Optional.of(enVigueur);
    } catch (JacksonException e) {
      LOG.warn("quota TM malformé en base — ignoré : {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Persiste le quota en base dans {@code app_config}.
   *
   * @param quota quota à sauvegarder
   */
  public void ecrireQuota(final QuotaTM quota) {
    try {
      final String json = objectMapper.writeValueAsString(quota);
      acces.ecrire(CLE_QUOTA, json);
    } catch (JacksonException e) {
      LOG.error("impossible de sérialiser le quota TM", e);
    }
  }

  /**
   * Vérifie le quota avant un job TM. Retourne {@code false} si le job doit
   * être annulé.
   *
   * @param nomJob nom du job pour les logs
   * @return {@code true} si le job peut continuer
   */
  public boolean verifier(final String nomJob) {
    final Optional<QuotaTM> quotaOpt = lireQuota();
    if (quotaOpt.isEmpty()) {
      return true;
    }

    final QuotaTM quota = quotaOpt.get();
    if (quota.dispo() < SEUIL_QUOTA_ABORT) {
      LOG.error(
          "quota TM épuisé — job annulé dispo={} total={} job={}",
          quota.dispo(),
          quota.total(),
          nomJob);
      logAdmin(
          "error",
          "Quota TM épuisé — job annulé : " + nomJob,
          Map.of(CLE_DISPO, quota.dispo(), CLE_TOTAL, quota.total(), "job", nomJob));
      return false;
    }
    if (quota.dispo() < SEUIL_QUOTA_WARN) {
      LOG.warn("quota TM faible dispo={} total={} job={}", quota.dispo(), quota.total(), nomJob);
      logAdmin(
          "warn",
          "Quota TM faible",
          Map.of(CLE_DISPO, quota.dispo(), CLE_TOTAL, quota.total(), "job", nomJob));
    }
    return true;
  }

  /**
   * Vérifie si les jobs cron sont autorisés (quota ≥ 500).
   *
   * @return {@code true} si le cron peut s'exécuter
   */
  public boolean cronAutorise() {
    final Optional<QuotaTM> quotaOpt = lireQuota();
    if (quotaOpt.isEmpty()) {
      return true;
    }
    final QuotaTM quota = quotaOpt.get();
    if (quota.dispo() < SEUIL_QUOTA_CRON) {
      LOG.warn("cron TM suspendu — quota insuffisant dispo={}", quota.dispo());
      logAdmin(
          "warn",
          "Cron TM suspendu — quota insuffisant",
          Map.of(CLE_DISPO, quota.dispo(), CLE_TOTAL, quota.total()));
      return false;
    }
    return true;
  }

  private void logAdmin(final String niveau, final String message, final Map<String, Object> meta) {
    logsAdmin.inserer(niveau, "worker", message, meta);
  }
}
