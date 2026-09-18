/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline complet des jobs {@code fetch:salle}/{@code fetch:salles:all}
 * : fetch par salle suivie, dédoublonnage, persistance par lot, suivi
 * des absences — extrait de {@link WorkerFetchService} pour rester
 * sous le seuil de couplage entre objets (20).
 * @since 2.9.1
 * @author Kwaite
 */
class FetchSallesExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchSallesExecutor.class);
  private static final int TAILLE_LOT_SALLES = 20;

  private final List<SourceConcerts> sources;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  /* package-private : instancié uniquement par Spring (même package). */
  FetchSallesExecutor(
      final List<SourceConcerts> sources,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final FetchRapportage rapportage,
      final WorkerFetchCommun commun) {
    this.sources = sources;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.rapportage = rapportage;
    this.commun = commun;
  }

  /**
   * Fetch une salle auprès de la source qui accepte son identifiant et
   * enregistre le résultat auprès de {@link SuiviSanteSourcesService} (phase
   * 1).
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
          rapportage
              .suiviSanteSourcesService()
              .enregistrerResultatSalle(identifiant, resultat.size());
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
   * Fetch les concerts d'une ou plusieurs salles par identifiants
   * préfixés séparés par une virgule (ex. "tm:id1,digitick:slug").
   * @param identifiantsStr identifiants préfixés séparés par des virgules
   */
  /* package-private : appelé par WorkerFetchService (même package). */
  void executerUneSalle(final String identifiantsStr) {
    LOG.info("fetch:salle démarré identifiants={}", identifiantsStr);
    if (!commun.obtenirGestionQuotaTM().verifier("fetch:salle")) {
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
   * Fetch périodique des salles suivies ayant des sources configurées, ou
   * retente un sous-ensemble ciblé (voir {@link RetryLotsService}) —
   * restreint aux salles réellement suivies (détail : CLAUDE.md, Jobs
   * background).
   * @param salleIdsCibles identifiants (salles.id) à retenter, ou null
   *     pour un cycle complet
   */
  /* package-private : appelé par WorkerFetchService (même package). */
  void executerToutes(final List<String> salleIdsCibles) {
    final boolean retry = salleIdsCibles != null;
    LOG.info("fetch:salles:all démarré{}", retry ? " (retry ciblé)" : "");
    rapportage
        .workerStatsService()
        .executerAvecStatsToleretErreur(
            LOG,
            "fetch:salles:all",
            () -> {
              if (!commun.obtenirGestionQuotaTM().cronAutorise()) {
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
              rapportage.notificationDigestService().executerDigest("temps_reel");
              LOG.info("fetch:salles:all terminé");
              return WorkerStatsService.ResultatJob.succes(resultat);
            });
  }

  /**
   * Salle suivie à refetcher, avec ses identifiants préfixés agrégés toutes
   * sources confondues.
   *
   * @param salleId UUID de la salle (texte), pour le suivi d'absences après
   *     persistance
   * @param identifiants identifiants préfixés séparés par des virgules
   */
  private record SalleAFetcher(String salleId, String identifiants) {}

  // Le préfixe émis ici doit correspondre à celui attendu par
  // TicketmasterSource.accepteIdentifiantSalle ("tm", pas la valeur canonique
  // "ticketmaster" de salle_sources.source) — sans cette traduction, fetchSalle
  // n'est jamais invoqué pour aucune salle TM (mismatch silencieux, aucune
  // exception, aucun log d'erreur). Restreint aux salles suivies (user_salles)
  // — voir Javadoc de executerToutes.
  private List<SalleAFetcher> recupererIdentifiantsSallesSuivies(
      final List<String> salleIdsCibles) {
    final boolean filtre = salleIdsCibles != null;
    return commun
        .obtenirJdbc()
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
   * Fetch + dédoublonnage + persistance d'un lot de salles ({@value
   * TAILLE_LOT_SALLES}) — persistance par lot plutôt qu'en bloc final,
   * chaque chaîne d'identifiants regroupe déjà toutes les sources d'une
   * même salle ({@code GROUP BY ss.salle_id}).
   * @param lotSalles sous-ensemble de salles suivies
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
