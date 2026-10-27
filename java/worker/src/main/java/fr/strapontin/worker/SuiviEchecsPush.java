/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.EchecPush;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Suites d'un envoi push côté worker : remise à zéro du compteur d'échecs,
 * révocation de l'appareil selon {@link EchecPush}, trace de la cause dans
 * les journaux et {@code logs_admin}.
 * @since 3.0.4
 * @author Kwaite
 */
final class SuiviEchecsPush {

  private static final Logger LOG = LoggerFactory.getLogger(SuiviEchecsPush.class);
  private static final String CLE_ID = "id";
  private static final String CLE_ENDPOINT = "endpoint";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /* package-private : instancié par les émetteurs push du même package. */
  SuiviEchecsPush(final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  private boolean panneDuService(final Map<String, Object> abonnement) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            EchecPush.SQL_PANNE_DU_SERVICE, Map.of(CLE_ID, abonnement.get(CLE_ID)), Boolean.class));
  }

  /* package-private : appelé par les émetteurs push du même package. */
  void succes(final Map<String, Object> abonnement) {
    jdbc.update(EchecPush.SQL_REINITIALISER_ECHECS, Map.of(CLE_ID, abonnement.get(CLE_ID)));
  }

  // Révocation immédiate : abonnement supprimé côté navigateur ou service push
  // non pris en charge.
  /* package-private : appelé par les émetteurs push du même package. */
  void revoquer(
      final Map<String, Object> abonnement,
      final String userId,
      final String cause,
      final String raison,
      final String source) {
    jdbc.update(EchecPush.SQL_SUPPRIMER_ABONNEMENT, Map.of(CLE_ID, abonnement.get(CLE_ID)));
    jdbc.update(EchecPush.SQL_DATER_RETRAIT, Map.of("userId", userId, "cause", cause));
    jdbc.update(EchecPush.SQL_DECOCHER_PUSH_SANS_APPAREIL, Map.of("userId", userId));
    LOG.info("Abonnement push révoqué ({}) : {}", raison, userId);
    final Map<String, Object> meta = new HashMap<>();
    meta.put("userId", userId);
    meta.put("raison", raison);
    meta.put("service", EchecPush.masquerEndpoint((String) abonnement.get(CLE_ENDPOINT)));
    logsAdminWorker.inserer(
        "warn",
        source,
        "abonnement push révoqué automatiquement (" + raison + ") userId=" + userId,
        meta);
  }

  // Un échec isolé peut être transitoire : seule une série assez longue
  // (EchecPush) révoque l'abonnement, sauf si tout le service échoue.
  /* package-private : appelé par les émetteurs push du même package. */
  void echec(
      final Map<String, Object> abonnement,
      final String userId,
      final Integer statut,
      final String corps,
      final String source) {
    final String raison = EchecPush.raison(statut, corps);
    final Map<String, Object> serie =
        jdbc.queryForMap(
            EchecPush.SQL_INCREMENTER_ECHECS,
            new MapSqlParameterSource()
                .addValue(CLE_ID, abonnement.get(CLE_ID))
                .addValue("statut", statut));
    final int echecs = EchecPush.echecs(serie);
    if (EchecPush.seuilAtteint(serie) && !panneDuService(abonnement)) {
      revoquer(
          abonnement,
          userId,
          EchecPush.causeLisible(statut),
          raison + ", " + echecs + " échecs consécutifs",
          source);
    } else {
      LOG.warn(
          "Push: échec {} ({}e consécutif) pour {} — {}",
          raison,
          echecs,
          userId,
          EchecPush.masquerEndpoint((String) abonnement.get(CLE_ENDPOINT)));
    }
  }
}
