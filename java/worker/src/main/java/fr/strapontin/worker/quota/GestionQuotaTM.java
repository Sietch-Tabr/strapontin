/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.QuotaTM;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Gestion du quota journalier Ticketmaster stocké dans {@code app_config}.
///
/// <p>Les seuils de protection sont :<br>
/// — {@code SEUIL_QUOTA_CRON = 500} : les jobs cron sont suspendus en dessous.<br>
/// — {@code SEUIL_QUOTA_ABORT = 50} : tous les jobs TM sont annulés en dessous.<br>
/// — {@code SEUIL_QUOTA_WARN = 200} : un avertissement est émis en dessous.
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

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public GestionQuotaTM(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /// Lit le quota courant depuis {@code app_config}.
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
      return Optional.of(objectMapper.readValue(json, QuotaTM.class));
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

  /// Vérifie le quota avant un job TM. Retourne {@code false} si le job doit être annulé.
  ///
  /// @param nomJob nom du job pour les logs
  /// @return {@code true} si le job peut continuer
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
      return false;
    }
    if (quota.dispo() < SEUIL_QUOTA_WARN) {
      log.warn("quota TM faible dispo={} total={} job={}", quota.dispo(), quota.total(), nomJob);
    }
    return true;
  }

  /// Vérifie si les jobs cron sont autorisés (quota ≥ {@value #SEUIL_QUOTA_CRON}).
  ///
  /// @return {@code true} si le cron peut s'exécuter
  public boolean cronAutorise() {
    return lireQuota().map(q -> q.dispo() >= SEUIL_QUOTA_CRON).orElse(true);
  }
}
