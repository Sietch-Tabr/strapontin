/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.sites.SourceSiteSalle;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestration des jobs de fetch : fetch → dédoublonnage → résolution →
 * persistance → recalcul — extrait de {@code WorkerService}, délégué
 * par {@code FetchArtistesBatchJob}/{@code FetchRegionJob}/{@code
 * FetchSalleJob}/{@code FetchSallesAllJob}.
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class WorkerFetchService {

  private final FetchArtistesBatchExecutor fetchArtistesBatchExecutor;
  private final FetchUnArtisteExecutor fetchUnArtisteExecutor;
  private final FetchRegionExecutor fetchRegionExecutor;
  private final FetchSallesExecutor fetchSallesExecutor;
  private final FetchSitesSallesExecutor fetchSitesSallesExecutor;

  /**
   * Injection Spring : sources de fetch, persistance/recalcul, reporting
   * (rapportage) et briques partagées (commun) — paramètres homonymes.
   */
  public WorkerFetchService(
      final List<SourceConcerts> sources,
      final List<SourceSiteSalle> sitesSalles,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final FetchRapportage rapportage,
      final WorkerFetchCommun commun) {
    this.fetchArtistesBatchExecutor =
        new FetchArtistesBatchExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
    this.fetchUnArtisteExecutor =
        new FetchUnArtisteExecutor(
            fetchArtistesBatchExecutor, recalculerMatchesService, rapportage, commun);
    this.fetchRegionExecutor =
        new FetchRegionExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
    this.fetchSallesExecutor =
        new FetchSallesExecutor(
            sources, persisterConcerts, recalculerMatchesService, rapportage, commun);
    this.fetchSitesSallesExecutor =
        new FetchSitesSallesExecutor(
            sitesSalles, persisterConcerts, recalculerMatchesService, rapportage, commun);
  }

  /**
   * Fetch global des artistes suivis (TM attractionId + Eventim) —
   * suspendu si quota TM &lt; 500. Une exception de lot n'interrompt pas
   * les suivants, recalcul/digest sur les lots réussis. Voir {@link
   * FetchArtistesBatchExecutor}.
   */
  public void executerFetchArtistesBatch() {
    fetchArtistesBatchExecutor.executer(null);
  }

  /**
   * Retente un sous-ensemble ciblé, en échec lors d'un cycle précédent —
   * voir {@link RetryLotsService}. Mêmes règles que le cycle complet.
   * @param artisteIdsCibles identifiants (artistes.id, texte) à retenter
   */
  public void executerFetchArtistesBatch(final List<String> artisteIdsCibles) {
    fetchArtistesBatchExecutor.executer(artisteIdsCibles);
  }

  /**
   * Récupère un seul artiste, à son ajout — voir {@link
   * FetchUnArtisteExecutor}.
   * @param artisteId identifiant ({@code artistes.id}, texte) de l'artiste
   */
  public void executerFetchArtiste(final String artisteId) {
    fetchUnArtisteExecutor.executer(artisteId);
  }

  /**
   * Fetch par région pour tous les départements configurés. Suspendu si le
   * quota TM est insuffisant. Voir {@link FetchRegionExecutor} .
   */
  public void executerFetchRegion() {
    fetchRegionExecutor.executer(null);
  }

  /**
   * Retente un sous-ensemble ciblé de départements, en échec lors d'un cycle
   * précédent — voir {@link RetryLotsService} .
   *
   * @param deptsCibles départements à retenter
   */
  public void executerFetchRegion(final List<String> deptsCibles) {
    fetchRegionExecutor.executer(deptsCibles);
  }

  /**
   * Fetch les concerts d'une ou plusieurs salles par identifiants
   * préfixés séparés par une virgule (ex. "tm:id1,eventim:internal:accor arena").
   * @param identifiantsStr identifiants préfixés séparés par des virgules
   */
  public void executerFetchSalle(final String identifiantsStr) {
    fetchSallesExecutor.executerUneSalle(identifiantsStr);
  }

  /**
   * Fetch périodique des salles suivies ({@code user_salles}) ayant des
   * sources configurées — restreint aux salles réellement suivies
   * (détail, incident quota TM : CLAUDE.md, Jobs background). Boucle et
   * agrège avant persistance ({@link #executerFetchSalle}), suspendu si
   * quota TM insuffisant.
   */
  public void executerFetchSallesAll() {
    fetchSallesExecutor.executerToutes(null);
  }

  /**
   * Retente un sous-ensemble ciblé de salles suivies, en échec lors d'un cycle
   * précédent — voir {@link RetryLotsService} .
   *
   * @param salleIdsCibles identifiants ({@code salles.id}, texte) à retenter
   */
  public void executerFetchSallesAll(final List<String> salleIdsCibles) {
    fetchSallesExecutor.executerToutes(salleIdsCibles);
  }

  /**
   * Lecture quotidienne des sites de salles qui vendent hors Ticketmaster et
   * Eventim — voir {@link FetchSitesSallesExecutor}.
   */
  public void executerFetchSitesSalles() {
    fetchSitesSallesExecutor.executer();
  }
}
