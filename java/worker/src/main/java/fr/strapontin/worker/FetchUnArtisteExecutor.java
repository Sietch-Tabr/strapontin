/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Récupère un seul artiste, à son ajout : ni statistiques de cycle, ni
 * retry, la dérive de volume et les séries d'échecs restant propres au
 * cron (même principe que {@code fetch:salle}).
 * @since 3.0.2
 * @author Kwaite
 */
class FetchUnArtisteExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchUnArtisteExecutor.class);

  private final FetchArtistesBatchExecutor lots;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  /* package-private : instancié par WorkerFetchService (même package). */
  FetchUnArtisteExecutor(
      final FetchArtistesBatchExecutor lots,
      final RecalculerMatchesService recalculerMatchesService,
      final FetchRapportage rapportage,
      final WorkerFetchCommun commun) {
    this.lots = lots;
    this.recalculerMatchesService = recalculerMatchesService;
    this.rapportage = rapportage;
    this.commun = commun;
  }

  /**
   * @param artisteId identifiant ({@code artistes.id}, texte) de l'artiste
   */
  /* package-private : appelé par WorkerFetchService (même package). */
  void executer(final String artisteId) {
    LOG.info("fetch:artiste démarré artisteId={}", artisteId);
    final List<Map<String, Object>> lot = lots.chargerArtistesSuivis(List.of(artisteId));
    if (lot.isEmpty()) {
      LOG.info("fetch:artiste — artiste non suivi artisteId={}", artisteId);
      return;
    }
    lots.traiterLotHorsCycle(lot, commun.obtenirGestionQuotaTM().verifier("fetch:artiste"));
    commun.sauvegarderQuota();
    recalculerMatchesService.recalculerMatches(null);
    rapportage.notificationDigestService().executerDigest("temps_reel");
    LOG.info("fetch:artiste terminé artisteId={}", artisteId);
  }
}
