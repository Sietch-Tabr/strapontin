/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.QuotaTM;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Gestion du quota journalier Ticketmaster stocké dans `app_config`.
///
/// Seuils de protection :
///
/// - `SEUIL_QUOTA_CRON = 500` : les jobs cron sont suspendus en dessous.
/// - `SEUIL_QUOTA_ABORT = 50` : tous les jobs TM sont annulés en dessous.
/// - `SEUIL_QUOTA_WARN = 200` : un avertissement est émis en dessous.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class GestionQuotaTM {

  private static final Logger log = LoggerFactory.getLogger(GestionQuotaTM.class);

  static final int SEUIL_QUOTA_CRON = 500;
  static final int SEUIL_QUOTA_ABORT = 50;
  static final int SEUIL_QUOTA_WARN = 200;

  private static final String CLE_QUOTA = "TM_QUOTA_JSON";
  private static final String CLE_DISPO = "dispo";
  private static final String CLE_TOTAL = "total";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public GestionQuotaTM(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /// Lit le quota courant depuis {@code app_config}.
  ///
  /// <p>Si le timestamp {@code Rate-Limit-Reset} (en ms) est dépassé, retourne un quota
  /// synthétique {@code dispo = total} et persiste la mise à jour pour débloquer les jobs cron.
  /// Le premier vrai appel TM réécrira la valeur réelle depuis les headers.
  ///
  /// @return quota lu, ou vide si absent ou malformé
  public Optional<QuotaTM> lireQuota() {
    try {
      String json =
          jdbc.queryForObject(
              "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
              Map.of("cle", CLE_QUOTA),
              String.class);
      if (json == null) return Optional.empty();
      QuotaTM quota = objectMapper.readValue(json, QuotaTM.class);
      if (quota.reset() > 0 && quota.reset() <= Instant.now().toEpochMilli()) {
        QuotaTM renouvele = new QuotaTM(quota.total(), quota.total(), 0L, Instant.now().toString());
        ecrireQuota(renouvele);
        log.info("quota TM renouvelé automatiquement après reset — dispo={}", renouvele.dispo());
        return Optional.of(renouvele);
      }
      return Optional.of(quota);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    } catch (JsonProcessingException e) {
      log.warn("quota TM malformé en base — ignoré : {}", e.getMessage());
      return Optional.empty();
    }
  }

  /// Persiste le quota en base dans {@code app_config}.
  ///
  /// @param quota quota à sauvegarder
  public void ecrireQuota(QuotaTM quota) {
    try {
      String json = objectMapper.writeValueAsString(quota);
      jdbc.update(
          """
          INSERT INTO app_config (cle, valeur_chiffree)
          VALUES (:cle, :json)
          ON CONFLICT (cle) DO UPDATE SET valeur_chiffree = :json, mis_a_jour_le = now()
          """,
          Map.of("cle", CLE_QUOTA, "json", json));
    } catch (JsonProcessingException e) {
      log.error("impossible de sérialiser le quota TM", e);
    }
  }

  /// Vérifie le quota avant un job TM. Retourne `false` si le job doit être annulé.
  ///
  /// @param nomJob nom du job pour les logs
  /// @return `true` si le job peut continuer
  public boolean verifier(String nomJob) {
    Optional<QuotaTM> quotaOpt = lireQuota();
    if (quotaOpt.isEmpty()) return true;

    QuotaTM quota = quotaOpt.get();
    if (quota.dispo() < SEUIL_QUOTA_ABORT) {
      log.error(
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
      log.warn("quota TM faible dispo={} total={} job={}", quota.dispo(), quota.total(), nomJob);
      logAdmin(
          "warn",
          "Quota TM faible",
          Map.of(CLE_DISPO, quota.dispo(), CLE_TOTAL, quota.total(), "job", nomJob));
    }
    return true;
  }

  /// Vérifie si les jobs cron sont autorisés (quota ≥ 500).
  ///
  /// @return `true` si le cron peut s'exécuter
  public boolean cronAutorise() {
    Optional<QuotaTM> quotaOpt = lireQuota();
    if (quotaOpt.isEmpty()) return true;
    QuotaTM quota = quotaOpt.get();
    if (quota.dispo() < SEUIL_QUOTA_CRON) {
      log.warn("cron TM suspendu — quota insuffisant dispo={}", quota.dispo());
      logAdmin(
          "warn",
          "Cron TM suspendu — quota insuffisant",
          Map.of(CLE_DISPO, quota.dispo(), CLE_TOTAL, quota.total()));
      return false;
    }
    return true;
  }

  private void logAdmin(String niveau, String message, Map<String, Object> meta) {
    try {
      String metaJson = objectMapper.writeValueAsString(meta);
      Map<String, Object> params = new HashMap<>();
      params.put("ts", Instant.now().toEpochMilli());
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
      log.warn("Erreur sérialisation meta log quota : {}", e.getMessage());
    } catch (Exception _) {
      log.warn("Impossible d'insérer log quota admin");
    }
  }
}
