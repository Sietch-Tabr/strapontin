/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestration des jobs de fetch : fetch → dédoublonnage → résolution → persistance → recalcul.
 * Extrait de {@code WorkerService}.
 *
 * <p>Délégué par les jobs Quartz {@code FetchArtistesBatchJob}, {@code FetchRegionJob}, {@code
 * FetchSalleJob}, {@code FetchSallesAllJob}.
 *
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class WorkerFetchService {

  private final FetchArtistesBatchExecutor fetchArtistesBatchExecutor;
  private final FetchRegionExecutor fetchRegionExecutor;
  private final FetchSallesExecutor fetchSallesExecutor;

  /**
   * @param sources sources de fetch injectées (une par source active)
   * @param persisterConcerts persistance des concerts dédoublonnés et résolus
   * @param recalculerMatchesService recalcul des matches utilisateur après persistance
   * @param rapportage collaborateurs de reporting partagés (notification, bilan de job, suivi
   *     passif des échecs par source)
   * @param commun briques partagées entre pipelines de fetch (jdbc, tm, quota, dédoublonnage, suivi
   *     des absences, retry)
   */
  public WorkerFetchService(
      final List<SourceConcerts> sources,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final FetchRapportage rapportage,
      final WorkerFetchCommun commun) {
    this.fetchArtistesBatchExecutor =
        new FetchArtistesBatchExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
    this.fetchRegionExecutor =
        new FetchRegionExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
    this.fetchSallesExecutor =
        new FetchSallesExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
  }

  /**
   * Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
   *
   * <p>Suspendu si le quota TM est inférieur à 500 (SEUIL_QUOTA_CRON). Une exception dans un lot
   * n'interrompt pas les lots suivants : le recalcul de matchs et le digest de notification
   * s'exécutent toujours sur la base des lots effectivement réussis, plutôt que d'être sautés en
   * cas d'échec partiel. Voir {@link FetchArtistesBatchExecutor}.
   */
  public void executerFetchArtistesBatch() {
    fetchArtistesBatchExecutor.executer(null);
  }

  /**
   * Retente un sous-ensemble ciblé d'artistes suivis, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}. Mêmes règles que le cycle complet (quota, dédoublonnage, persistance
   * par lot), restreintes aux identifiants donnés.
   *
   * @param artisteIdsCibles identifiants ({@code artistes.id}, texte) à retenter
   */
  public void executerFetchArtistesBatch(final List<String> artisteIdsCibles) {
    fetchArtistesBatchExecutor.executer(artisteIdsCibles);
  }

  /**
   * Fetch par région pour tous les départements configurés. Suspendu si le quota TM est
   * insuffisant. Voir {@link FetchRegionExecutor}.
   */
  public void executerFetchRegion() {
    fetchRegionExecutor.executer(null);
  }

  /**
   * Retente un sous-ensemble ciblé de départements, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}.
   *
   * @param deptsCibles départements à retenter
   */
  public void executerFetchRegion(final List<String> deptsCibles) {
    fetchRegionExecutor.executer(deptsCibles);
  }

  /**
   * Fetch les concerts d'une ou plusieurs salles identifiées par leurs identifiants préfixés.
   *
   * <p>Le paramètre est une liste d'identifiants séparés par une virgule, chacun préfixé par la
   * source : {@code "tm:rZFSnyZdFvA"} ou {@code "tm:id1,digitick:slug"}.
   *
   * @param identifiantsStr identifiants préfixés séparés par des virgules
   */
  public void executerFetchSalle(final String identifiantsStr) {
    fetchSallesExecutor.executerUneSalle(identifiantsStr);
  }

  /**
   * Fetch périodique des salles suivies ({@code user_salles}) ayant des sources configurées dans
   * {@code salle_sources}.
   *
   * <p>Restreint aux salles réellement suivies par un utilisateur depuis — jusque-là toutes les
   * salles du référentiel (255 avec source TM en prod le 18/07/2026) étaient refetchées à chaque
   * cycle, épuisant le quota TM en une journée dès que a débloqué cet appel pour de vrai. Les
   * salles non suivies n'apportent aucune valeur ici : un artiste suivi qui y jouerait est de toute
   * façon déjà détecté par {@code fetch:artistes:batch} (recherche nationale par nom, indépendante
   * du département).
   *
   * <p>Boucle sur les salles suivies et exécute le même pipeline que {@link #executerFetchSalle} en
   * agrégeant les concerts avant persistance. Suspendu si le quota TM est insuffisant.
   */
  public void executerFetchSallesAll() {
    fetchSallesExecutor.executerToutes(null);
  }

  /**
   * Retente un sous-ensemble ciblé de salles suivies, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}.
   *
   * @param salleIdsCibles identifiants ({@code salles.id}, texte) à retenter
   */
  public void executerFetchSallesAll(final List<String> salleIdsCibles) {
    fetchSallesExecutor.executerToutes(salleIdsCibles);
  }
}
