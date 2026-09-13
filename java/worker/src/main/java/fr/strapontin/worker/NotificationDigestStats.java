/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Journalisation du bilan de cycle ({@code logs_admin}) et des statistiques ({@code fetch_jobs}) du
 * job {@code notifier:digest} — extrait de {@link NotificationDigestService} pour rester sous le
 * seuil de couplage entre objets (20).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class NotificationDigestStats {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationDigestStats.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper jackson;

  /*
   * package-private : instancié uniquement par NotificationDigestService (même
   * package).
   */
  NotificationDigestStats(final NamedParameterJdbcTemplate jdbc, final ObjectMapper jackson) {
    this.jdbc = jdbc;
    this.jackson = jackson;
  }

  /* package-private : appelé par NotificationDigestService (même package). */
  void logAdminDigest(final int nbEmails, final int nbPush, final int nbErreurs) {
    try {
      final String metaJson =
          jackson.writeValueAsString(
              Map.of("nb_emails", nbEmails, "nb_push", nbPush, "nb_erreurs", nbErreurs));
      final String message = "digest — " + nbEmails + " email(s), " + nbPush + " push envoyés";
      final String niveau = nbErreurs > 0 ? "warn" : "info";
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          new MapSqlParameterSource()
              .addValue("ts", Instant.now().toEpochMilli())
              .addValue("niveau", niveau)
              .addValue("source", "worker")
              .addValue("message", message)
              .addValue("meta", metaJson));
    } catch (Exception e) {
      LOG.warn("Impossible d'écrire log admin digest : {}", e.getMessage());
    }
  }

  /* package-private : appelé par NotificationDigestService (même package). */
  void enregistrerStat(final long dureeMs, final int nbNotifies, final String erreur) {
    try {
      final Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", "notifier:digest");
      params.put("now", Timestamp.from(Instant.now()));
      params.put("dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE));
      params.put("nbNotifies", nbNotifies);
      params.put("erreur", erreur);
      jdbc.update(
          """
          INSERT INTO fetch_jobs (type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, 0, :nbNotifies, :erreur)
          ON CONFLICT (type_job) DO UPDATE SET
            derniere_exec  = EXCLUDED.derniere_exec,
            duree_ms       = EXCLUDED.duree_ms,
            nb_inseres     = EXCLUDED.nb_inseres,
            nb_mis_a_jour  = EXCLUDED.nb_mis_a_jour,
            erreur         = EXCLUDED.erreur
          """,
          params);
    } catch (Exception e) {
      LOG.warn("Impossible d'enregistrer stat notifier:digest : {}", e.getMessage());
    }
  }

  /* package-private : appelé par NotificationDigestService (même package). */
  static String formaterDate(final String dateIso) {
    final LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
