/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Horaire de chaque job planifié, worker (Quartz) et web, en un seul endroit
 * et dans un seul fuseau. L'ordre des chaînes de purge est annoncé par la note
 * de chaque horaire. Un test compare ces horaires à la documentation.
 * @since 3.1.0
 * @author Kwaite
 */
public final class HorairesJobs {

  /** Fuseau de tous les horaires. */
  public static final String FUSEAU = "Europe/Paris";

  private static final String HEURES_CYCLE = "1,5,7,9,11,13,15,17,19,21,23";

  /** Cycle de récupération : onze fois par jour. */
  public static final String FETCH_ARTISTES = "0 0 " + HEURES_CYCLE + " * * ?";

  /** Récupération par département : un passage nocturne. */
  public static final String FETCH_REGION = "0 0 2 * * ?";

  /** Salles suivies, une demi-heure après chaque cycle d'artistes. */
  public static final String FETCH_SALLES = "0 30 " + HEURES_CYCLE + " * * ?";

  /** Sites de salles : en journée, jamais la nuit. */
  public static final String FETCH_SITES_SALLES = "0 0 14 * * ?";

  /** Correspondances utilisateurs. */
  public static final String RECALCULER_MATCHES = "0 0 " + HEURES_CYCLE + " * * ?";

  /** Contrôle et enrichissement nocturnes, après la récupération. */
  public static final String RECONCILIATION_NOCTURNE = "0 0 3 * * ?";

  /** Purge des concerts : début de la chaîne nocturne. */
  public static final String PURGER_CONCERTS = "0 0 0 * * ?";

  /** Purge des salles orphelines, après celle des concerts. */
  public static final String PURGER_SALLES = "0 5 0 * * ?";

  /** Purge des artistes orphelins, après celle des salles. */
  public static final String PURGER_ARTISTES = "0 6 0 * * ?";

  /** Purge des journaux, après celle des artistes. */
  public static final String PURGER_LOGS_ADMIN = "0 15 0 * * ?";

  /** Comptes inactifs. */
  public static final String COMPTES_INACTIFS = "0 20 0 * * ?";

  /** Invitations non activées. */
  public static final String INVITES_EXPIRES = "0 25 0 * * ?";

  /** Révocations d'administrateurs arrivées à échéance. */
  public static final String REVOQUER_ADMINS = "0 0 * * * ?";

  /** Surveillance des cycles de jobs, à la demie. */
  public static final String SUIVI_CYCLES = "0 30 * * * ?";

  /** Digest quotidien. */
  public static final String DIGEST_QUOTIDIEN = "0 0 12 * * ?";

  /** Envois push différés par la plage silencieuse. */
  public static final String DIGEST_DIFFERES = "0 0 7 * * ?";

  /** Digest hebdomadaire. */
  public static final String DIGEST_HEBDOMADAIRE = "0 0 12 ? * SUN";

  /** Annonce d'une nouvelle version. */
  public static final String NOTIFIER_VERSION = "0 0 11 * * ?";

  /** Contrôle web de l'activité du worker. */
  public static final String WATCHDOG_WORKER = "0 0 * * * *";

  /** Digest web des erreurs de journaux. */
  public static final String ALERTE_LOGS_ADMIN = "0 */15 * * * *";

  /** Alerte web de quota Resend. */
  public static final String ALERTE_QUOTA_RESEND = "0 */15 * * * *";

  /** Jobs du worker, dans l'ordre de la journée. */
  public static final List<Horaire> WORKER =
      List.of(
          new Horaire(
              "purger:concerts", PURGER_CONCERTS, null, "1re purge, avant fetch et réconciliation"),
          new Horaire("purger:salles", PURGER_SALLES, null, "après purger:concerts"),
          new Horaire("purger:artistes", PURGER_ARTISTES, null, "après purger:salles"),
          new Horaire("purger:logs-admin", PURGER_LOGS_ADMIN, null, "après purger:artistes"),
          new Horaire("gerer:comptes-inactifs", COMPTES_INACTIFS, null, ""),
          new Horaire("gerer:invites-expires", INVITES_EXPIRES, null, ""),
          new Horaire("revoquer:admins", REVOQUER_ADMINS, null, ""),
          new Horaire("suivi:cycles-jobs", SUIVI_CYCLES, null, ""),
          new Horaire("fetch:artistes:batch", FETCH_ARTISTES, "worker.cron.fetch-artistes", ""),
          new Horaire("fetch:salles:all", FETCH_SALLES, "worker.cron.fetch-salles", ""),
          new Horaire(
              "recalculer:matches", RECALCULER_MATCHES, "worker.cron.recalculer-matches", ""),
          new Horaire("fetch:region", FETCH_REGION, "worker.cron.fetch-region", ""),
          new Horaire(
              "reconciliation:nocturne",
              RECONCILIATION_NOCTURNE,
              "worker.cron.reconciliation-nocturne",
              "après fetch:region"),
          new Horaire("notifier:digest (différés)", DIGEST_DIFFERES, null, "push retenus la nuit"),
          new Horaire("notifier:version", NOTIFIER_VERSION, null, ""),
          new Horaire("notifier:digest (quotidien)", DIGEST_QUOTIDIEN, null, ""),
          new Horaire("notifier:digest (hebdomadaire)", DIGEST_HEBDOMADAIRE, null, ""),
          new Horaire(
              "fetch:sites-salles",
              FETCH_SITES_SALLES,
              "worker.cron.fetch-sites-salles",
              "en journée"));

  /** Tâches du module web. */
  public static final List<Horaire> WEB =
      List.of(
          new Horaire("watchdog:worker", WATCHDOG_WORKER, null, ""),
          new Horaire("alerte:logs-admin", ALERTE_LOGS_ADMIN, null, ""),
          new Horaire("alerte:quota-resend", ALERTE_QUOTA_RESEND, null, ""));

  private HorairesJobs() {}

  /**
   * Tableau Markdown des horaires, tel que reproduit dans la documentation du
   * worker entre ses deux repères.
   * @return le tableau, une ligne par horaire
   */
  public static String tableau() {
    final List<String> lignes = new ArrayList<>();
    lignes.add("| Tâche | Horaire (" + FUSEAU + ") | Surcharge | Note |");
    lignes.add("| --- | --- | --- | --- |");
    for (final Horaire h : concat()) {
      final String surcharge = h.surcharge() == null ? "" : "`" + h.surcharge() + "`";
      lignes.add(
          "| `" + h.type() + "` | `" + h.cron() + "` | " + surcharge + " | " + h.note() + " |");
    }
    return String.join("\n", lignes) + "\n";
  }

  private static List<Horaire> concat() {
    return java.util.stream.Stream.concat(WORKER.stream(), WEB.stream()).toList();
  }

  /**
   * Un job planifié.
   *
   * @param type identifiant du job ({@code fetch_jobs.type_job}) ou tâche web
   * @param cron expression à six champs (secondes en tête)
   * @param surcharge propriété qui remplace l'horaire, ou {@code null}
   * @param note ordre à respecter ou précision, peut être vide
   */
  public record Horaire(String type, String cron, String surcharge, String note) {}
}
