/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.QuotaTM;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/// HealthIndicator custom exposant l'état du quota TM et le statut des sources de fetch via {@code
/// /actuator/health} — un incident métier critique (quota épuisé, source bloquée en amont par un
/// blocage réseau, boucle d'envois défaillante) n'était visible jusqu'ici que via {@code
/// logs_admin} ou email, jamais interrogeable par un outil de monitoring externe sondant l'endpoint
/// standard.
///
/// <p>Implémentation volontairement minimale : ne remplace pas {@code SuiviSanteSourcesService}
/// (compteurs d'échecs par salle/artiste, health-check applicatif complet), juste un reflet agrégé
/// pour un premier coup d'œil externe.
///
/// @since 2.7.0
/// @author Kwaite
@Component("sources")
public class SourcesHealthIndicator implements HealthIndicator {

  // Miroir de GestionQuotaTM.SEUIL_QUOTA_ABORT (package-private, non réutilisable ici) — sous ce
  // seuil, les jobs TM sont annulés côté worker : un état DOWN est justifié.
  private static final int SEUIL_QUOTA_CRITIQUE = 50;

  private final GestionQuotaTM gestionQuotaTM;
  private final NamedParameterJdbcTemplate jdbc;

  public SourcesHealthIndicator(GestionQuotaTM gestionQuotaTM, NamedParameterJdbcTemplate jdbc) {
    this.gestionQuotaTM = gestionQuotaTM;
    this.jdbc = jdbc;
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();

    Optional<QuotaTM> quota = gestionQuotaTM.lireQuota();
    quota.ifPresent(
        q -> {
          builder.withDetail("quotaTmDispo", q.dispo());
          builder.withDetail("quotaTmTotal", q.total());
          if (q.dispo() < SEUIL_QUOTA_CRITIQUE) {
            builder.down().withDetail("quotaTm", "épuisé");
          }
        });

    List<Map<String, Object>> jobsEnErreur =
        jdbc.queryForList(
            """
            SELECT type_job, erreur FROM fetch_jobs
            WHERE type_job LIKE 'fetch:%' AND erreur IS NOT NULL
            """,
            Map.of());
    if (!jobsEnErreur.isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      for (Map<String, Object> ligne : jobsEnErreur) {
        details.put((String) ligne.get("type_job"), ligne.get("erreur"));
      }
      builder.down().withDetail("sourcesEnErreur", details);
    }

    return builder.build();
  }
}
