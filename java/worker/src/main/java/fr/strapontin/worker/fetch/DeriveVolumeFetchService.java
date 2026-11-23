/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.fetch;

import fr.strapontin.worker.LogsAdminWorker;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Détecte un repli de volume significatif d'un cycle de fetch réussi
 * par rapport à son historique récent (détail : CLAUDE.md,
 * Observabilité des jobs).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class DeriveVolumeFetchService {

  private static final Logger LOG = LoggerFactory.getLogger(DeriveVolumeFetchService.class);

  /**
   * Nombre d'exécutions réussies récentes sur lesquelles porte la médiane
   * glissante. La médiane ignore les cycles périodiques de relecture des gros
   * lieux, qui gonfleraient une moyenne au-dessus du volume normal.
   */
  private static final int NB_EXECUTIONS_MOYENNE = 5;

  /**
   * Écart relatif à partir duquel un cycle est considéré en repli significatif
   * — 30% absorbe la variance normale (concerts qui expirent, creux
   * saisonniers) sans masquer une dégradation partielle silencieuse.
   */
  private static final double SEUIL_DERIVE = 0.30;

  /**
   * Sous cette médiane, l'écart relatif n'est plus discriminant (ex. 1 -> 0
   * concert est un repli de 100% sans rien d'anormal) — aucune comparaison
   * n'est faite en dessous de ce volume moyen.
   */
  private static final int VOLUME_MOYEN_MIN_SIGNIFICATIF = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation d'un repli de volume détecté
   */
  public DeriveVolumeFetchService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Compare le volume du cycle qui vient de réussir à la médiane de ses
   * {@value #NB_EXECUTIONS_MOYENNE} dernières exécutions réussies.
   * @param typeJob type de job (ex. "fetch:region")
   * @param volumeCourant insertions + mises à jour du cycle qui réussit
   * @param avant horodatage du cycle courant (exclu de la moyenne)
   */
  public void verifier(final String typeJob, final int volumeCourant, final Instant avant) {
    final List<Integer> historique = lireVolumesRecents(typeJob, avant);
    if (historique.size() < NB_EXECUTIONS_MOYENNE) {
      return;
    }

    final double mediane = calculerMediane(historique);
    if (mediane < VOLUME_MOYEN_MIN_SIGNIFICATIF) {
      return;
    }
    if (volumeCourant >= mediane * (1 - SEUIL_DERIVE)) {
      return;
    }

    LOG.warn(
        "derive:volume typeJob={} volume={} mediane{}={}",
        typeJob,
        volumeCourant,
        NB_EXECUTIONS_MOYENNE,
        mediane);
    logsAdminWorker.inserer(
        "warn",
        "derive-volume",
        "volume du cycle significativement inférieur à la médiane récente typeJob="
            + typeJob
            + " volume="
            + volumeCourant
            + " mediane"
            + NB_EXECUTIONS_MOYENNE
            + "="
            + mediane,
        Map.of("typeJob", typeJob, "volume", volumeCourant, "medianeRecente", mediane));
  }

  private static double calculerMediane(final List<Integer> volumes) {
    final List<Integer> tries = volumes.stream().sorted().toList();
    final int milieu = tries.size() / 2;
    return tries.size() % 2 == 1
        ? tries.get(milieu)
        : (tries.get(milieu - 1) + tries.get(milieu)) / 2.0;
  }

  private List<Integer> lireVolumesRecents(final String typeJob, final Instant avant) {
    return jdbc.query(
        """
        SELECT nb_inseres + nb_mis_a_jour AS volume
        FROM fetch_jobs_historique
        WHERE type_job = :typeJob AND erreur IS NULL AND NOT retry_cible AND exec_le < :avant
        ORDER BY exec_le DESC
        LIMIT :limite
        """,
        // pgjdbc n'infère pas le type SQL d'un java.time.Instant nu en
        // paramètre de bind — OffsetDateTime est le seul type JSR-310 supporté
        // automatiquement en écriture par le driver.
        Map.of(
            "typeJob",
            typeJob,
            "avant",
            avant.atOffset(ZoneOffset.UTC),
            "limite",
            NB_EXECUTIONS_MOYENNE),
        (rs, _) -> rs.getInt("volume"));
  }
}
