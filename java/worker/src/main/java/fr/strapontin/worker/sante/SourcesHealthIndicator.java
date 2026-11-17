/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.QuotaTM;
import fr.strapontin.worker.maintenance.WorkerStatsService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Expose l'état quota TM/sources via {@code /actuator/health}
 * (détail : CLAUDE.md, Santé/référentiel admin).
 * @since 2.7.0
 * @author Kwaite
 */
@Component("sources")
public class SourcesHealthIndicator implements HealthIndicator {

  // Miroir de GestionQuotaTM.SEUIL_QUOTA_ABORT (package-private, non
  // réutilisable ici) — sous ce seuil, les jobs TM sont annulés côté worker :
  // un état DOWN est justifié.
  private static final int SEUIL_QUOTA_CRITIQUE = 50;

  private final GestionQuotaTM gestionQuotaTM;
  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param gestionQuotaTM état du quota Ticketmaster
   * @param jdbc accès JDBC
   */
  public SourcesHealthIndicator(
      final GestionQuotaTM gestionQuotaTM, final NamedParameterJdbcTemplate jdbc) {
    this.gestionQuotaTM = gestionQuotaTM;
    this.jdbc = jdbc;
  }

  @Override
  public Health health() {
    final Health.Builder builder = Health.up();

    final Optional<QuotaTM> quota = gestionQuotaTM.lireQuota();
    quota.ifPresent(
        q -> {
          builder.withDetail("quotaTmDispo", q.dispo());
          builder.withDetail("quotaTmTotal", q.total());
          if (q.dispo() < SEUIL_QUOTA_CRITIQUE) {
            builder.down().withDetail("quotaTm", "épuisé");
          }
        });

    final List<Map<String, Object>> jobsEnErreur =
        jdbc.queryForList(
            """
            SELECT type_job, erreur FROM fetch_jobs
            WHERE type_job LIKE 'fetch:%' AND erreur IS NOT NULL
              AND erreur <> :motifQuota
            """,
            Map.of("motifQuota", WorkerStatsService.ResultatJob.MOTIF_QUOTA_INSUFFISANT));
    if (!jobsEnErreur.isEmpty()) {
      final Map<String, Object> details = new LinkedHashMap<>();
      for (final Map<String, Object> ligne : jobsEnErreur) {
        details.put((String) ligne.get("type_job"), ligne.get("erreur"));
      }
      builder.down().withDetail("sourcesEnErreur", details);
    }

    return builder.build();
  }
}
