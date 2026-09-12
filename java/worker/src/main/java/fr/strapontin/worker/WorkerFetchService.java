/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

  private static final Logger LOG = LoggerFactory.getLogger(WorkerFetchService.class);

  private static final int TAILLE_LOT_SALLES = 20;

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NotificationDigestService notificationDigestService;
  private final NamedParameterJdbcTemplate jdbc;
  private final SuiviSanteSourcesService suiviSanteSourcesService;
  private final WorkerStatsService workerStatsService;
  private final WorkerFetchCommun commun;
  private final FetchArtistesBatchExecutor fetchArtistesBatchExecutor;
  private final FetchRegionExecutor fetchRegionExecutor;

  /**
   * @param sources sources de fetch injectées (une par source active)
   * @param tm fetch par lot d'attractionIds Ticketmaster
   * @param resolveur résolution du nom canonique artiste/salle
   * @param persisterConcerts persistance des concerts dédoublonnés et résolus
   * @param recalculerMatchesService recalcul des matches utilisateur après persistance
   * @param gestionQuotaTM lecture du quota Ticketmaster restant
   * @param notificationDigestService déclenchement des notifications immédiates
   * @param jdbc accès JDBC
   * @param suiviSanteSourcesService suivi passif des échecs de fetch par salle/artiste
   * @param workerStatsService journalisation du bilan de chaque job
   * @param logsAdminWorker journalisation des échecs de lots
   * @param retryLotsService retry ciblé des lots en échec
   */
  public WorkerFetchService(
      final List<SourceConcerts> sources,
      final TicketmasterSource tm,
      final ResolveurNomCanonique resolveur,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final GestionQuotaTM gestionQuotaTM,
      final NotificationDigestService notificationDigestService,
      final NamedParameterJdbcTemplate jdbc,
      final SuiviSanteSourcesService suiviSanteSourcesService,
      final WorkerStatsService workerStatsService,
      final LogsAdminWorker logsAdminWorker,
      final RetryLotsService retryLotsService) {
    this.sources = sources;
    this.tm = tm;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.notificationDigestService = notificationDigestService;
    this.jdbc = jdbc;
    this.suiviSanteSourcesService = suiviSanteSourcesService;
    this.workerStatsService = workerStatsService;
    this.commun =
        new WorkerFetchCommun(
            jdbc, resolveur, logsAdminWorker, retryLotsService, tm, gestionQuotaTM);
    this.fetchArtistesBatchExecutor =
        new FetchArtistesBatchExecutor(
            sources,
            tm,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            jdbc,
            workerStatsService,
            suiviSanteSourcesService,
            commun);
    this.fetchRegionExecutor =
        new FetchRegionExecutor(
            jdbc,
            sources,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            workerStatsService,
            commun);
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
   * Fetch une salle auprès de la source qui accepte son identifiant et enregistre le résultat
   * auprès de {@link SuiviSanteSourcesService} (phase 1).
   *
   * @param identifiant identifiant préfixé {@code source:identifiant_externe}
   * @param destination liste dans laquelle ajouter les concerts trouvés
   */
  private void fetchEtSuivreSalle(final String identifiant, final List<ConcertBrut> destination) {
    for (final SourceConcerts source : sources) {
      if (source.accepteIdentifiantSalle(identifiant)) {
        try {
          final List<ConcertBrut> resultat = source.fetchSalle(identifiant);
          destination.addAll(resultat);
          suiviSanteSourcesService.enregistrerResultatSalle(identifiant, resultat.size());
        } catch (Exception e) {
          LOG.error(
              "{} fetchSalle échoué identifiant={} : {}",
              source.nom(),
              identifiant,
              e.getMessage());
        }
      }
    }
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
    LOG.info("fetch:salle démarré identifiants={}", identifiantsStr);
    if (!gestionQuotaTM.verifier("fetch:salle")) {
      return;
    }

    final String[] identifiants = identifiantsStr.split(",");
    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final String identifiant : identifiants) {
      fetchEtSuivreSalle(identifiant.trim(), concerts);
    }
    commun.sauvegarderQuota();

    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(concerts, LOG);
    LOG.info(
        "fetch:salle identifiants={} bruts={} dédoublonnés={}",
        identifiantsStr,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    recalculerMatchesService.recalculerMatches(null);
    LOG.info("fetch:salle terminé identifiants={}", identifiantsStr);
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
    executerFetchSallesAllInterne(null);
  }

  /**
   * Retente un sous-ensemble ciblé de salles suivies, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}.
   *
   * @param salleIdsCibles identifiants ({@code salles.id}, texte) à retenter
   */
  public void executerFetchSallesAll(final List<String> salleIdsCibles) {
    executerFetchSallesAllInterne(salleIdsCibles);
  }

  private void executerFetchSallesAllInterne(final List<String> salleIdsCibles) {
    final boolean retry = salleIdsCibles != null;
    LOG.info("fetch:salles:all démarré{}", retry ? " (retry ciblé)" : "");
    workerStatsService.executerAvecStatsToleretErreur(
        LOG,
        "fetch:salles:all",
        () -> {
          if (!gestionQuotaTM.cronAutorise()) {
            LOG.warn("fetch:salles:all suspendu — quota TM insuffisant");
            return new WorkerStatsService.ResultatJob(
                new ResultatPersistance(0, 0), "quota insuffisant");
          }

          final List<SalleAFetcher> sallesAFetcher =
              recupererIdentifiantsSallesSuivies(salleIdsCibles);
          if (sallesAFetcher.isEmpty()) {
            LOG.info("fetch:salles:all — aucune salle suivie avec source configurée");
            return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
          }

          final ResultatPersistance resultat = traiterTousLesLotsSalles(sallesAFetcher);
          commun.sauvegarderQuota();
          recalculerMatchesService.recalculerMatches(null);
          notificationDigestService.executerDigest("temps_reel");
          LOG.info("fetch:salles:all terminé");
          return WorkerStatsService.ResultatJob.succes(resultat);
        });
  }

  /**
   * Salle suivie à refetcher, avec ses identifiants préfixés agrégés toutes sources confondues.
   *
   * @param salleId UUID de la salle (texte), pour le suivi d'absences après persistance
   * @param identifiants identifiants préfixés séparés par des virgules
   */
  private record SalleAFetcher(String salleId, String identifiants) {}

  // Le préfixe émis ici doit correspondre à celui attendu par
  // TicketmasterSource.accepteIdentifiantSalle ("tm", pas la valeur canonique "ticketmaster" de
  // salle_sources.source) — sans cette traduction, fetchSalle n'est jamais invoqué pour aucune
  // salle TM (mismatch silencieux, aucune exception, aucun log d'erreur). Restreint aux salles
  // suivies (user_salles) — voir Javadoc de executerFetchSallesAll.
  private List<SalleAFetcher> recupererIdentifiantsSallesSuivies(
      final List<String> salleIdsCibles) {
    final boolean filtre = salleIdsCibles != null;
    return jdbc
        .queryForList(
            "SELECT ss.salle_id::text AS salle_id,"
                + " string_agg(ss.source || ':' || ss.identifiant_externe, ',') AS identifiants"
                + " FROM salle_sources ss"
                + " WHERE EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = ss.salle_id)"
                + (filtre ? " AND ss.salle_id::text IN (:ids)" : "")
                + " GROUP BY ss.salle_id",
            filtre ? Map.of("ids", salleIdsCibles) : Map.of())
        .stream()
        .map(
            ligne ->
                new SalleAFetcher(
                    (String) ligne.get("salle_id"),
                    ((String) ligne.get("identifiants"))
                        .replace("ticketmaster:", TicketmasterSource.PREFIXE_IDENTIFIANT + ":")))
        .toList();
  }

  private ResultatPersistance traiterTousLesLotsSalles(final List<SalleAFetcher> sallesAFetcher) {
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    final List<String> echecsLots = new ArrayList<>();
    final List<String> salleIdsEnEchec = new ArrayList<>();
    final int totalLots = (sallesAFetcher.size() + TAILLE_LOT_SALLES - 1) / TAILLE_LOT_SALLES;
    for (int i = 0; i < sallesAFetcher.size(); i += TAILLE_LOT_SALLES) {
      final List<SalleAFetcher> lotSalles =
          sallesAFetcher.subList(i, Math.min(i + TAILLE_LOT_SALLES, sallesAFetcher.size()));
      try {
        resultat = WorkerFetchCommun.accumuler(resultat, traiterLotSalles(lotSalles));
      } catch (Exception e) {
        LOG.error(
            "fetch:salles:all lot {}-{} échoué : {}", i, i + lotSalles.size(), e.getMessage(), e);
        echecsLots.add("lot " + i + "-" + (i + lotSalles.size()) + " : " + e.getMessage());
        lotSalles.forEach(salle -> salleIdsEnEchec.add(salle.salleId()));
      }
    }
    commun.remonterEchecsLots("fetch:salles:all", echecsLots, totalLots, salleIdsEnEchec);
    return resultat;
  }

  /**
   * Fetch + dédoublonnage + persistance d'un lot de salles (taille {@value TAILLE_LOT_SALLES}).
   *
   * <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que {@link
   * #traiterLotArtistesBatch}) : chaque chaîne d'identifiants regroupe déjà toutes les sources
   * d'une même salle ({@code GROUP BY ss.salle_id}), le dédoublonnage cross-sources reste donc
   * correct à cette granularité.
   *
   * @param lotSalles sous-ensemble de salles suivies (taille {@value TAILLE_LOT_SALLES})
   * @return bilan de persistance du lot
   */
  private ResultatPersistance traiterLotSalles(final List<SalleAFetcher> lotSalles) {
    final List<ConcertBrut> tous = new ArrayList<>();
    for (final SalleAFetcher salle : lotSalles) {
      for (final String id : salle.identifiants().split(",")) {
        fetchEtSuivreSalle(id.trim(), tous);
      }
    }

    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(tous, LOG);
    LOG.info(
        "fetch:salles:all salles={} bruts={} dédoublonnés={}",
        lotSalles.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    final List<String> salleIds = lotSalles.stream().map(SalleAFetcher::salleId).toList();
    commun.mettreAJourAbsences("salle_id", salleIds, debutLot);
    return resultat;
  }
}
