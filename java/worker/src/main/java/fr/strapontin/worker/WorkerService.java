/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.sante.ReconciliationNocturneService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/// Orchestration des jobs de recalcul de matches et de réconciliation nocturne.
///
/// <p>Depuis, le fetch (voir {@link WorkerFetchService}), la purge (voir {@link
/// WorkerPurgeService}) et la révocation admin (voir {@link WorkerRevocationService}) ont été
/// extraits dans des services dédiés — cette classe reste le point d'entrée fin délégué par les
/// jobs Quartz {@code RecalculerMatchesJob} et {@code ReconciliationNocturneJob}, les deux seules
/// méthodes ne partageant pas de logique privée substantielle avec les autres familles.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class WorkerService {

  private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

  private final RecalculerMatchesService recalculerMatchesService;
  private final NotificationDigestService notificationDigestService;
  private final ReconciliationNocturneService reconciliationNocturneService;
  private final WorkerStatsService workerStatsService;

  public WorkerService(
      RecalculerMatchesService recalculerMatchesService,
      NotificationDigestService notificationDigestService,
      ReconciliationNocturneService reconciliationNocturneService,
      WorkerStatsService workerStatsService) {
    this.recalculerMatchesService = recalculerMatchesService;
    this.notificationDigestService = notificationDigestService;
    this.reconciliationNocturneService = reconciliationNocturneService;
    this.workerStatsService = workerStatsService;
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// @param userIds IDs à recalculer, ou `null` pour tous
  public void executerRecalculerMatches(List<String> userIds) {
    log.info("recalculer:matches démarré userIds={}", userIds);
    workerStatsService.executerAvecStats(
        log,
        "recalculer:matches",
        () -> {
          int nbUsers = recalculerMatchesService.recalculerMatches(userIds);
          notificationDigestService.executerDigest("temps_reel");
          log.info("recalculer:matches terminé users={}", nbUsers);
          return new ResultatPersistance(0, 0);
        });
  }

  /// Job nocturne de réconciliation des sources salles/artistes : health-check et redécouverte des
  /// identifiants suspects, puis enrichissement Wikidata/OSM/Deezer.
  public void executerReconciliationNocturne() {
    log.info("reconciliation:nocturne démarré");
    workerStatsService.executerAvecStats(
        log,
        "reconciliation:nocturne",
        () -> {
          var bilan = reconciliationNocturneService.executer();
          log.info(
              "reconciliation:nocturne terminé sallesVerifiees={} redecouvertes={} ambigues={}"
                  + " enrichissementsSalle={} enrichissementsArtiste={} typesWikidataInvalides={}"
                  + " nomsIncoherentsTM={} contaminationsMultiSalleSignalees={}"
                  + " doublonsMultiSalleSignales={}",
              bilan.sallesVerifiees(),
              bilan.sallesRedecouvertes(),
              bilan.ambigues(),
              bilan.enrichissementsSalle(),
              bilan.enrichissementsArtiste(),
              bilan.typesWikidataInvalides(),
              bilan.nomsIncoherentsTM(),
              bilan.contaminationsMultiSalleSignalees(),
              bilan.doublonsMultiSalleSignales());
          return new ResultatPersistance(
              0,
              bilan.sallesRedecouvertes()
                  + bilan.enrichissementsSalle()
                  + bilan.enrichissementsArtiste());
        });
  }
}
