/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.planification;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Correspondance entre la clé Quartz d'un job (nom du bean déclencheur) et son
 * nom lisible ({@code type_job}, utilisé dans {@code logs_admin} /{@code
 * fetch_jobs}) — table unique partagée par {@link LogAdminJobListener} et
 * {@link TriggerController} , jusque-là dupliquée et désynchronisée entre les
 * deux.
 */
public final class NomsJobs {

  /** Clé Quartz vers type_job. */
  public static final Map<String, String> PAR_CLE_QUARTZ =
      Map.ofEntries(
          Map.entry("fetchArtistesBatch", "fetch:artistes:batch"),
          Map.entry("fetchRegion", "fetch:region"),
          Map.entry("fetchSallesAll", "fetch:salles:all"),
          Map.entry("fetchSitesSalles", "fetch:sites-salles"),
          Map.entry("purgerConcerts", "purger:concerts"),
          Map.entry("purgerSalles", "purger:salles"),
          Map.entry("purgerArtistes", "purger:artistes"),
          Map.entry("purgerLogsAdmin", "purger:logs-admin"),
          Map.entry("gererComptesInactifs", "gerer:comptes-inactifs"),
          Map.entry("gererInvitesExpires", "gerer:invites-expires"),
          Map.entry("revoquerAdmins", "revoquer:admins"),
          Map.entry("recalculerMatches", "recalculer:matches"),
          Map.entry("notifierDigest", "notifier:digest"),
          Map.entry("notifierVersion", "notifier:version"),
          Map.entry("reconciliationNocturne", "reconciliation:nocturne"),
          Map.entry("suiviCyclesJobs", "suivi:cycles-jobs"));

  /** Clé Quartz vers type_job. */
  public static final Map<String, String> CLE_QUARTZ_PAR_TYPE_JOB =
      PAR_CLE_QUARTZ.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

  private NomsJobs() {}
}
