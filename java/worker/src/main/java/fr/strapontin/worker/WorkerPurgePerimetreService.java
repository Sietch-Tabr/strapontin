/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Purge des fiches à venir que plus aucun fetch ne revoit : département suivi
 * par personne, ni artiste, ni salle, ni correspondance d'un utilisateur. Elles
 * se recréent d'elles-mêmes si un utilisateur choisit le département.
 * @since 3.1.0
 * @author Kwaite
 */
@Service
public class WorkerPurgePerimetreService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerPurgePerimetreService.class);

  // Les fetchs de région passent plusieurs fois par jour : deux semaines sans
  // aucune observation revue signent une fiche hors de leur portée.
  private static final int SEUIL_JOURS_SANS_OBSERVATION = 14;

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param workerStatsService journalisation du bilan de purge
   * @param logsAdminWorker trace de chaque suppression
   */
  public WorkerPurgePerimetreService(
      final NamedParameterJdbcTemplate jdbc,
      final WorkerStatsService workerStatsService,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Supprime les fiches à venir hors du périmètre de tout utilisateur et sans
   * observation revue depuis {@value #SEUIL_JOURS_SANS_OBSERVATION} jours.
   */
  public void executerPurgerConcertsHorsPerimetre() {
    LOG.info("purger:concerts-hors-perimetre démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts-hors-perimetre",
        () -> {
          final int supprimes =
              jdbc.update(
                  """
                  DELETE FROM evenements e
                  WHERE e.date >= CURRENT_DATE AND e.dept IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM user_config uc WHERE e.dept = ANY(uc.departements))
                    AND NOT EXISTS (SELECT 1 FROM user_artistes ua WHERE ua.artiste_id = e.artiste_id)
                    AND NOT EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = e.salle_id)
                    AND NOT EXISTS (SELECT 1 FROM evenement_artistes ea
                                    JOIN user_artistes ua ON ua.artiste_id = ea.artiste_id
                                    WHERE ea.evenement_id = e.id)
                    AND NOT EXISTS (SELECT 1 FROM user_matches um WHERE um.concert_id = e.id)
                    AND NOT EXISTS (SELECT 1 FROM observations o
                                    WHERE o.evenement_id = e.id
                                      AND o.updated_at > now() - make_interval(days => :seuilJours))
                  """,
                  Map.of("seuilJours", SEUIL_JOURS_SANS_OBSERVATION));
          if (supprimes > 0) {
            logsAdminWorker.inserer(
                "info",
                "purge-concerts",
                supprimes + " fiche(s) à venir hors périmètre supprimée(s)",
                Map.of("supprimes", supprimes));
          }
          LOG.info("purger:concerts-hors-perimetre terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }
}
