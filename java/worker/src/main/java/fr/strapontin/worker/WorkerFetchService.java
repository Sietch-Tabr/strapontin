/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceAvecFetchLot;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.EligibiliteResolutionDeezer;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static final Logger log = LoggerFactory.getLogger(WorkerFetchService.class);

  private static final int DELAI_ENTRE_ARTISTES_MS = 250;
  private static final int TAILLE_LOT_TM = 20;
  private static final int TAILLE_LOT_DEPTS = 5;
  private static final int TAILLE_LOT_SALLES = 20;

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final ResolveurNomCanonique resolveur;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NotificationDigestService notificationDigestService;
  private final NamedParameterJdbcTemplate jdbc;
  private final SuiviSanteSourcesService suiviSanteSourcesService;
  private final WorkerStatsService workerStatsService;
  private final LogsAdminWorker logsAdminWorker;
  private final RetryLotsService retryLotsService;

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
    this.resolveur = resolveur;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.notificationDigestService = notificationDigestService;
    this.jdbc = jdbc;
    this.suiviSanteSourcesService = suiviSanteSourcesService;
    this.workerStatsService = workerStatsService;
    this.logsAdminWorker = logsAdminWorker;
    this.retryLotsService = retryLotsService;
  }

  /**
   * Remonte dans {@code logs_admin} les échecs de lots d'un job multi-lots, indépendamment du
   * résultat des autres lots — sans cela, un lot en échec reste invisible tant que le job dans son
   * ensemble se termine sans exception et que son bilan agrégé reste positif grâce aux autres lots.
   *
   * <p>Niveau {@code error} si tous les lots du job ont échoué (panne totale), {@code warn} sinon
   * (échec partiel, même convention que {@code WorkerStatsService#remonterBilan}). Délègue ensuite
   * à {@link RetryLotsService} la décision de retenter ou d'abandonner les identifiants en échec, y
   * compris quand ce cycle est propre (remise à zéro du compteur de tentatives).
   *
   * @param typeJob type de job (ex. {@code "fetch:salles:all"})
   * @param echecs messages d'échec, un par lot en échec
   * @param totalLots nombre total de lots traités par le job
   * @param identifiantsEnEchec identifiants des entités des lots en échec, pour un retry ciblé
   */
  private void remonterEchecsLots(
      final String typeJob,
      final List<String> echecs,
      final int totalLots,
      final List<String> identifiantsEnEchec) {
    if (!echecs.isEmpty()) {
      final String niveau = echecs.size() == totalLots ? "error" : "warn";
      final String message =
          typeJob
              + " — "
              + echecs.size()
              + "/"
              + totalLots
              + " lot(s) échoué(s) : "
              + String.join(" | ", echecs);
      logsAdminWorker.inserer(niveau, "worker", message, Map.of("echecs", echecs));
    }
    retryLotsService.gererResultat(typeJob, identifiantsEnEchec);
  }

  /**
   * Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
   *
   * <p>Suspendu si le quota TM est inférieur à 500 (SEUIL_QUOTA_CRON). Une exception dans un lot
   * n'interrompt pas les lots suivants : le recalcul de matchs et le digest de notification
   * s'exécutent toujours sur la base des lots effectivement réussis, plutôt que d'être sautés en
   * cas d'échec partiel.
   */
  public void executerFetchArtistesBatch() {
    executerFetchArtistesBatchInterne(null);
  }

  /**
   * Retente un sous-ensemble ciblé d'artistes suivis, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}. Mêmes règles que le cycle complet (quota, dédoublonnage, persistance
   * par lot), restreintes aux identifiants donnés.
   *
   * @param artisteIdsCibles identifiants ({@code artistes.id}, texte) à retenter
   */
  public void executerFetchArtistesBatch(final List<String> artisteIdsCibles) {
    executerFetchArtistesBatchInterne(artisteIdsCibles);
  }

  private void executerFetchArtistesBatchInterne(final List<String> artisteIdsCibles) {
    final boolean retry = artisteIdsCibles != null;
    log.info("fetch:artistes:batch démarré{}", retry ? " (retry ciblé)" : "");
    workerStatsService.executerAvecStatsToleretErreur(
        log,
        "fetch:artistes:batch",
        () -> {
          if (!gestionQuotaTM.cronAutorise()) {
            log.warn("fetch:artistes:batch suspendu — quota TM insuffisant");
            return new WorkerStatsService.ResultatJob(
                new ResultatPersistance(0, 0), "quota insuffisant");
          }

          final List<Map<String, Object>> rows =
              jdbc.queryForList(
                  "SELECT ua.artiste_id::text AS artiste_id_str,"
                      + " MAX(ua.artiste_affiche) AS artiste_affiche,"
                      + " MAX(asm.identifiant_externe) AS attraction_id,"
                      + " COUNT(DISTINCT asm.identifiant_externe) AS nb_ids_distincts,"
                      + " MAX(a.deezer_id) AS deezer_id,"
                      + " MAX(a.wikidata_id) AS wikidata_id"
                      + " FROM user_artistes ua"
                      + " JOIN artistes a ON a.id = ua.artiste_id"
                      + " LEFT JOIN artiste_sources asm"
                      + "   ON asm.artiste_id = ua.artiste_id AND asm.source = 'ticketmaster'"
                      + (retry ? " WHERE ua.artiste_id::text IN (:ids)" : "")
                      + " GROUP BY ua.artiste_id",
                  retry ? Map.of("ids", artisteIdsCibles) : Map.of());

          if (rows.isEmpty()) {
            log.info("fetch:artistes:batch — aucun artiste suivi");
            return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
          }

          final List<SourceConcerts> sourcesSimples =
              sources.stream().filter(s -> !(s instanceof SourceAvecFetchLot)).toList();

          ResultatPersistance resultat = new ResultatPersistance(0, 0);
          final List<String> echecsLots = new ArrayList<>();
          final List<String> idsEnEchec = new ArrayList<>();
          final int totalLots = (rows.size() + TAILLE_LOT_TM - 1) / TAILLE_LOT_TM;
          for (int i = 0; i < rows.size(); i += TAILLE_LOT_TM) {
            final List<Map<String, Object>> lot =
                rows.subList(i, Math.min(i + TAILLE_LOT_TM, rows.size()));
            try {
              resultat = accumuler(resultat, traiterLotArtistesBatch(lot, sourcesSimples));
            } catch (Exception e) {
              log.error(
                  "fetch:artistes:batch lot {}-{} échoué : {}",
                  i,
                  i + lot.size(),
                  e.getMessage(),
                  e);
              echecsLots.add("lot " + i + "-" + (i + lot.size()) + " : " + e.getMessage());
              lot.forEach(row -> idsEnEchec.add((String) row.get("artiste_id_str")));
            }
          }
          remonterEchecsLots("fetch:artistes:batch", echecsLots, totalLots, idsEnEchec);
          sauvegarderQuota();
          recalculerMatchesService.recalculerMatches(null);
          notificationDigestService.executerDigest("temps_reel");
          log.info("fetch:artistes:batch terminé");
          return WorkerStatsService.ResultatJob.succes(resultat);
        });
  }

  /**
   * Fetch + dédoublonnage + persistance d'un lot d'artistes (taille {@value TAILLE_LOT_TM}).
   *
   * <p>Persistance par lot plutôt qu'en un seul bloc final : sans cela, une interruption
   * (redéploiement, crash) avant la fin du cycle complet perdrait tout le travail de fetch déjà
   * effectué sur ce cycle (quota TM consommé pour rien). Le dédoublonnage cross-sources
   * (TM/Eventim) reste correct à cette granularité : un même concert n'est jamais rapporté par deux
   * sources pour deux artistes différents, la comparaison par lot suffit.
   *
   * @param lot sous-ensemble de lignes artiste (taille {@value TAILLE_LOT_TM})
   * @param sourcesSimples sources hors {@link SourceAvecFetchLot} (fetch individuel par artiste)
   * @return bilan de persistance du lot
   */
  private ResultatPersistance traiterLotArtistesBatch(
      final List<Map<String, Object>> lot, final List<SourceConcerts> sourcesSimples) {
    final List<ConcertBrut> concertsTM = fetchTMParArtistes(lot);

    final List<ConcertBrut> concertsAutresSources = new ArrayList<>();
    for (final SourceConcerts source : sourcesSimples) {
      for (final Map<String, Object> row : lot) {
        final String nom = (String) row.get("artiste_affiche");
        final String deezerId = (String) row.get("deezer_id");
        final String wikidataId = (String) row.get("wikidata_id");
        try {
          concertsAutresSources.addAll(source.fetchArtiste(nom, deezerId, wikidataId));
        } catch (Exception e) {
          log.warn("{} fetchArtiste échoué artiste={} : {}", source.nom(), nom, e.getMessage());
        }
        attendreRateLimit();
      }
    }

    final List<ConcertBrut> tous = new ArrayList<>(concertsTM);
    tous.addAll(concertsAutresSources);

    final List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:artistes:batch lot={} bruts={} dédoublonnés={}",
        lot.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    final List<String> artisteIds =
        lot.stream().map(row -> (String) row.get("artiste_id_str")).toList();
    mettreAJourAbsences("artiste_id", artisteIds, debutLot);
    return resultat;
  }

  // Nombre d'absences consécutives (cycles fetch:artistes:batch/fetch:salles:all successifs sans
  // qu'un concert précédemment vu ne réapparaisse) à partir duquel il est marqué comme non
  // confirmé récemment — évite un faux positif sur un simple aléa réseau isolé, tout en restant
  // assez bas pour signaler une disparition réelle dans un délai raisonnable (cron ~11 cycles/jour
  // — 5 absences correspond à environ une demi-journée de silence continu). Package-private :
  // référencé par WorkerPurgeService pour dériver le seuil de purge définitive (voir
  // WorkerPurgeService.SEUIL_PURGE_ABSENCES).
  static final int SEUIL_ABSENCES_CONSECUTIVES = 5;

  /**
   * Met à jour le compteur d'absences consécutives des concerts futurs liés aux entités
   * effectivement retraitées dans ce cycle : remis à zéro pour tout concert réellement revu ({@code
   * updated_at} avancé par l'upsert), incrémenté sinon.
   *
   * @param colonne "artiste_id" ou "salle_id"
   * @param ids identifiants (texte) des entités traitées dans ce cycle
   * @param debutCycle horodatage capturé avant la persistance du lot
   */
  private static final String SQL_ABSENCES_BASE =
      "UPDATE evenements SET"
          + " absences_consecutives = CASE WHEN updated_at >= CAST(:debut AS timestamptz)"
          + "   THEN 0 ELSE absences_consecutives + 1 END,"
          + " absence_detectee_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   WHEN absences_consecutives + 1 >= :seuil THEN COALESCE(absence_detectee_le, now())"
          + "   ELSE absence_detectee_le END,"
          // Un concert flagué pour suppression puis effectivement reconfirmé (ex. après fusion
          // d'un doublon de salle) n'a plus de raison de rester marqué suppression_suggeree_le.
          + " suppression_suggeree_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   ELSE suppression_suggeree_le END"
          + " WHERE %s::text IN (:ids) AND date >= CURRENT_DATE";

  private static final String SQL_ABSENCES_ARTISTE = SQL_ABSENCES_BASE.formatted("artiste_id");
  private static final String SQL_ABSENCES_SALLE = SQL_ABSENCES_BASE.formatted("salle_id");

  private void mettreAJourAbsences(
      final String colonne, final List<String> ids, final String debutCycle) {
    if (ids.isEmpty()) {
      return;
    }
    final String sql =
        switch (colonne) {
          case "artiste_id" -> SQL_ABSENCES_ARTISTE;
          case "salle_id" -> SQL_ABSENCES_SALLE;
          default -> throw new IllegalArgumentException("colonne inconnue : " + colonne);
        };
    jdbc.update(sql, Map.of("debut", debutCycle, "seuil", SEUIL_ABSENCES_CONSECUTIVES, "ids", ids));
  }

  private static ResultatPersistance accumuler(
      final ResultatPersistance a, final ResultatPersistance b) {
    return new ResultatPersistance(a.nbInseres() + b.nbInseres(), a.nbMisAJour() + b.nbMisAJour());
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
          log.error(
              "{} fetchSalle échoué identifiant={} : {}",
              source.nom(),
              identifiant,
              e.getMessage());
        }
      }
    }
  }

  /**
   * Fetch TM pour une liste d'artistes suivis.
   *
   * <p>Deux passes :
   *
   * <ol>
   *   <li>Résolution des {@code attractionId} manquants (1 requête /attractions.json par artiste
   *       non résolu), puis fetch groupé par lots de {@value TAILLE_LOT_TM} via {@code
   *       fetchArtistesBatch} — réduit la consommation de quota d'un facteur ×20 par rapport à un
   *       fetch artiste-par-artiste.
   *   <li>Fallback keyword ({@code fetchArtiste}) pour les artistes sans {@code attractionId} TM.
   * </ol>
   *
   * @param rows lignes agrégées par {@code artiste_id} avec {@code artiste_affiche}, {@code
   *     artiste_id_str}, {@code attraction_id}, {@code nb_ids_distincts}
   * @return concerts bruts collectés depuis TM
   */
  private record ResolutionAttractionIds(List<String> attractionIds, List<String> nomsSansTmId) {}

  private List<ConcertBrut> fetchTMParArtistes(final List<Map<String, Object>> rows) {
    final ResolutionAttractionIds resolution = resoudreAttractionIds(rows);
    final List<ConcertBrut> resultats =
        new ArrayList<>(fetchParLotsAttractionIds(resolution.attractionIds()));
    resultats.addAll(fetchParNomsSansAttractionId(resolution.nomsSansTmId()));
    return resultats;
  }

  // artiste_sources n'a pas de contrainte d'unicité sur artiste_id (PK = (source,
  // identifiant_externe)) — une fusion mal dédupliquée peut laisser deux identifiants TM pour le
  // même artiste, rendant le MAX() de la requête appelante arbitraire. Mieux vaut ne pas fetcher
  // avec un identifiant potentiellement faux que de rapatrier silencieusement les concerts d'un
  // autre artiste sous cette identité.
  private ResolutionAttractionIds resoudreAttractionIds(final List<Map<String, Object>> rows) {
    final List<String> attractionIds = new ArrayList<>();
    final List<String> nomsSansTmId = new ArrayList<>();

    for (final Map<String, Object> row : rows) {
      final String nom = (String) row.get("artiste_affiche");
      final String artisteIdStr = (String) row.get("artiste_id_str");
      String attractionId = (String) row.get("attraction_id");
      if (attractionIdAmbigu(row, nom, artisteIdStr)) {
        continue;
      }
      if (attractionId == null) {
        attractionId = rechercherEtEnregistrerAttractionId(nom, artisteIdStr);
      }
      if (attractionId != null) {
        attractionIds.add(attractionId);
      } else {
        nomsSansTmId.add(nom);
      }
    }
    return new ResolutionAttractionIds(attractionIds, nomsSansTmId);
  }

  private boolean attractionIdAmbigu(
      final Map<String, Object> row, final String nom, final String artisteIdStr) {
    final Number nbIdsDistinctsNum = (Number) row.get("nb_ids_distincts");
    final int nbIdsDistincts = nbIdsDistinctsNum != null ? nbIdsDistinctsNum.intValue() : 0;
    if (nbIdsDistincts <= 1) {
      return false;
    }
    log.warn(
        "artiste_sources ambigu — {} identifiant_externe ticketmaster distincts pour"
            + " artiste={} artisteId={}, fetch TM ignoré ce cycle",
        nbIdsDistincts,
        nom,
        artisteIdStr);
    jdbc.update(
        "UPDATE artiste_sources SET necessite_intervention_le ="
            + " COALESCE(necessite_intervention_le, now()) WHERE artiste_id = CAST(:artisteId"
            + " AS uuid) AND source = 'ticketmaster'",
        Map.of("artisteId", artisteIdStr));
    return true;
  }

  private String rechercherEtEnregistrerAttractionId(final String nom, final String artisteIdStr) {
    String attractionId = null;
    try {
      attractionId = tm.rechercherAttractionId(nom);
      if (attractionId != null) {
        jdbc.update(
            "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)"
                + " VALUES (CAST(:artisteId AS uuid), 'ticketmaster', :attractionId)"
                + " ON CONFLICT DO NOTHING",
            Map.of("artisteId", artisteIdStr, "attractionId", attractionId));
      }
    } catch (Exception e) {
      log.warn("rechercherAttractionId échoué artiste={} : {}", nom, e.getMessage());
    }
    // Throttle nécessaire ici : un lot avec plusieurs artistes jamais résolus peut générer une
    // rafale d'appels /attractions.json sans délai sinon.
    attendreRateLimit();
    return attractionId;
  }

  private List<ConcertBrut> fetchParLotsAttractionIds(final List<String> attractionIds) {
    final List<ConcertBrut> resultats = new ArrayList<>();
    for (int i = 0; i < attractionIds.size(); i += TAILLE_LOT_TM) {
      final List<String> lot =
          attractionIds.subList(i, Math.min(i + TAILLE_LOT_TM, attractionIds.size()));
      try {
        final List<ConcertBrut> concertsLot = tm.fetchArtistesLot(lot);
        resultats.addAll(concertsLot);
        suiviSanteSourcesService.enregistrerResultatArtistesLot(lot, concertsLot);
      } catch (Exception e) {
        log.warn("TM fetchArtistesLot lot={} échoué : {}", lot.size(), e.getMessage());
      }
    }
    return resultats;
  }

  private List<ConcertBrut> fetchParNomsSansAttractionId(final List<String> nomsSansTmId) {
    final List<ConcertBrut> resultats = new ArrayList<>();
    for (final String nom : nomsSansTmId) {
      try {
        resultats.addAll(tm.fetchArtiste(nom));
      } catch (Exception e) {
        log.warn("TM fetchArtiste échoué artiste={} : {}", nom, e.getMessage());
      }
      attendreRateLimit();
    }
    return resultats;
  }

  /**
   * Fetch par région pour tous les départements configurés.
   *
   * <p>Suspendu si le quota TM est insuffisant.
   */
  public void executerFetchRegion() {
    executerFetchRegionInterne(null);
  }

  /**
   * Retente un sous-ensemble ciblé de départements, en échec lors d'un cycle précédent — voir
   * {@link RetryLotsService}.
   *
   * @param deptsCibles départements à retenter
   */
  public void executerFetchRegion(final List<String> deptsCibles) {
    executerFetchRegionInterne(deptsCibles);
  }

  private void executerFetchRegionInterne(final List<String> deptsCibles) {
    final boolean retry = deptsCibles != null;
    log.info("fetch:region démarré{}", retry ? " (retry ciblé)" : "");
    workerStatsService.executerAvecStatsToleretErreur(
        log,
        "fetch:region",
        () -> {
          if (!gestionQuotaTM.cronAutorise()) {
            log.warn("fetch:region suspendu — quota TM insuffisant");
            return new WorkerStatsService.ResultatJob(
                new ResultatPersistance(0, 0), "quota insuffisant");
          }

          List<String> deptsListe;
          if (retry) {
            deptsListe = deptsCibles;
          } else {
            final List<String[]> allDepts =
                jdbc.query(
                    "SELECT departements FROM user_config",
                    Map.of(),
                    (rs, _) -> (String[]) rs.getArray("departements").getArray());
            deptsListe = new ArrayList<>(collecterTousLesDepts(allDepts));
          }

          if (deptsListe.isEmpty()) {
            log.info("fetch:region — aucun département configuré");
            return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
          }

          ResultatPersistance resultat = new ResultatPersistance(0, 0);
          final List<String> echecsLots = new ArrayList<>();
          final List<String> deptsEnEchec = new ArrayList<>();
          final int totalLots = (deptsListe.size() + TAILLE_LOT_DEPTS - 1) / TAILLE_LOT_DEPTS;
          for (int i = 0; i < deptsListe.size(); i += TAILLE_LOT_DEPTS) {
            final List<String> lotDepts =
                deptsListe.subList(i, Math.min(i + TAILLE_LOT_DEPTS, deptsListe.size()));
            try {
              resultat = accumuler(resultat, traiterLotDepartements(lotDepts));
            } catch (Exception e) {
              log.error("fetch:region lot {} échoué : {}", lotDepts, e.getMessage(), e);
              echecsLots.add("depts=" + lotDepts + " : " + e.getMessage());
              deptsEnEchec.addAll(lotDepts);
            }
          }
          remonterEchecsLots("fetch:region", echecsLots, totalLots, deptsEnEchec);
          sauvegarderQuota();
          recalculerMatchesService.recalculerMatches(null);
          notificationDigestService.executerDigest("temps_reel");
          log.info("fetch:region terminé");
          return WorkerStatsService.ResultatJob.succes(resultat);
        });
  }

  private static Set<String> collecterTousLesDepts(final List<String[]> allDepts) {
    final Set<String> tousLesDepts = new LinkedHashSet<>();
    for (final String[] depts : allDepts) {
      if (depts != null) {
        for (final String d : depts) {
          if (d != null && !d.isBlank()) {
            tousLesDepts.add(d.trim());
          }
        }
      }
    }
    return tousLesDepts;
  }

  /**
   * Fetch + dédoublonnage + persistance d'un lot de départements (taille {@value
   * TAILLE_LOT_DEPTS}).
   *
   * <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que {@link
   * #traiterLotArtistesBatch}) : le dédoublonnage cross-sources reste correct à cette granularité,
   * un même concert n'étant jamais rapporté par deux sources dans deux départements différents.
   *
   * <p>Met à jour les absences des salles du lot indépendamment de tout suivi utilisateur — {@code
   * fetch:region} est browse-only, un concert découvert ici peut n'être jamais suivi ni par artiste
   * ni par salle, auquel cas il n'entrerait autrement dans le périmètre d'aucun suivi de
   * péremption.
   *
   * @param lotDepts sous-ensemble de départements (taille {@value TAILLE_LOT_DEPTS})
   * @return bilan de persistance du lot
   */
  private ResultatPersistance traiterLotDepartements(final List<String> lotDepts) {
    final List<ConcertBrut> tous = new ArrayList<>();
    for (final SourceConcerts source : sources) {
      try {
        tous.addAll(source.fetchRegion(lotDepts, null));
      } catch (Exception e) {
        log.warn("{} fetchRegion échoué depts={} : {}", source.nom(), lotDepts, e.getMessage());
      }
    }

    final List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:region depts={} bruts={} dédoublonnés={}",
        lotDepts.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    mettreAJourAbsences("salle_id", sallesConnuesDesDepartements(lotDepts), debutLot);
    return resultat;
  }

  /**
   * Salles déjà connues (concerts futurs déjà en base) pour un lot de départements — inclut les
   * salles non suivies, contrairement à {@link #recupererIdentifiantsSallesSuivies}.
   */
  private List<String> sallesConnuesDesDepartements(final List<String> depts) {
    return jdbc.queryForList(
        "SELECT DISTINCT salle_id::text FROM concerts"
            + " WHERE dept IN (:depts) AND salle_id IS NOT NULL AND date >= CURRENT_DATE",
        Map.of("depts", depts),
        String.class);
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
    log.info("fetch:salle démarré identifiants={}", identifiantsStr);
    if (!gestionQuotaTM.verifier("fetch:salle")) {
      return;
    }

    final String[] identifiants = identifiantsStr.split(",");
    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final String identifiant : identifiants) {
      fetchEtSuivreSalle(identifiant.trim(), concerts);
    }
    sauvegarderQuota();

    final List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(concerts);
    log.info(
        "fetch:salle identifiants={} bruts={} dédoublonnés={}",
        identifiantsStr,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    recalculerMatchesService.recalculerMatches(null);
    log.info("fetch:salle terminé identifiants={}", identifiantsStr);
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
    log.info("fetch:salles:all démarré{}", retry ? " (retry ciblé)" : "");
    workerStatsService.executerAvecStatsToleretErreur(
        log,
        "fetch:salles:all",
        () -> {
          if (!gestionQuotaTM.cronAutorise()) {
            log.warn("fetch:salles:all suspendu — quota TM insuffisant");
            return new WorkerStatsService.ResultatJob(
                new ResultatPersistance(0, 0), "quota insuffisant");
          }

          final List<SalleAFetcher> sallesAFetcher =
              recupererIdentifiantsSallesSuivies(salleIdsCibles);
          if (sallesAFetcher.isEmpty()) {
            log.info("fetch:salles:all — aucune salle suivie avec source configurée");
            return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
          }

          final ResultatPersistance resultat = traiterTousLesLotsSalles(sallesAFetcher);
          sauvegarderQuota();
          recalculerMatchesService.recalculerMatches(null);
          notificationDigestService.executerDigest("temps_reel");
          log.info("fetch:salles:all terminé");
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
        resultat = accumuler(resultat, traiterLotSalles(lotSalles));
      } catch (Exception e) {
        log.error(
            "fetch:salles:all lot {}-{} échoué : {}", i, i + lotSalles.size(), e.getMessage(), e);
        echecsLots.add("lot " + i + "-" + (i + lotSalles.size()) + " : " + e.getMessage());
        lotSalles.forEach(salle -> salleIdsEnEchec.add(salle.salleId()));
      }
    }
    remonterEchecsLots("fetch:salles:all", echecsLots, totalLots, salleIdsEnEchec);
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

    final List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:salles:all salles={} bruts={} dédoublonnés={}",
        lotSalles.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    final List<String> salleIds = lotSalles.stream().map(SalleAFetcher::salleId).toList();
    mettreAJourAbsences("salle_id", salleIds, debutLot);
    return resultat;
  }

  /**
   * Détermine si un concert est éligible à la résolution du nom canonique via Deezer.
   *
   * <p>Exclut les classifications "theatre", "divers" et "sports" ({@link
   * #CLASSIFICATIONS_NON_MUSICALES}, jamais un vrai musicien côté Deezer) ainsi que, spécifiquement
   * pour Eventim, une classification vide/absente — Digitick omet aussi sa classification mais
   * uniquement pour de vrais concerts (filtré en amont sur le genre "Concert"), alors qu'Eventim
   * l'omet fréquemment y compris pour des spectacles non musicaux.
   *
   * @param c concert à évaluer
   * @return {@code true} si la résolution Deezer peut s'appliquer à ce concert
   */
  private static boolean estEligibleResolutionDeezer(final ConcertBrut c) {
    return EligibiliteResolutionDeezer.estEligible(c.classification(), c.source());
  }

  /**
   * Rejette un candidat Deezer divergent du {@code nom_affiche} Wikidata déjà connu pour cet
   * artiste : un mauvais rapprochement Deezer est une cause de corruption déjà rencontrée (ex. «
   * Nino Arial » → « Niño Árbol »), détectable avant la persistance quand l'identité de l'artiste
   * est déjà établie ailleurs par une source plus fiable (Wikidata).
   *
   * @param nomBrut nom brut entrant, avant résolution Deezer
   * @param nomDeezer nom canonique proposé par {@link ResolveurNomCanonique}
   * @return {@code nomDeezer}, ou {@code nomBrut} si un {@code nom_affiche} déjà connu diverge
   */
  private String nomCanoniqueValideOuBrut(final String nomBrut, final String nomDeezer) {
    if (nomDeezer.equals(nomBrut)) {
      return nomDeezer;
    }
    final List<String> nomsAffiches =
        jdbc.query(
            "SELECT nom_affiche FROM artistes WHERE nom_norm = :nomNorm AND nom_affiche IS NOT"
                + " NULL",
            Map.of("nomNorm", Normalisation.normaliserTexte(nomBrut)),
            (rs, _) -> rs.getString("nom_affiche"));
    if (nomsAffiches.isEmpty()) {
      return nomDeezer;
    }
    final String nomAffiche = nomsAffiches.get(0);
    if (CorrespondanceArtiste.estDoublonArtiste(
        Normalisation.normaliserTexte(nomAffiche), Normalisation.normaliserTexte(nomDeezer))) {
      return nomDeezer;
    }
    log.info(
        "résolution Deezer rejetée — divergence avec nom_affiche Wikidata connu brut={} deezer={}"
            + " nomAffiche={}",
        nomBrut,
        nomDeezer,
        nomAffiche);
    return nomBrut;
  }

  private List<ConcertBrut> resoudreEtDeduplicer(final List<ConcertBrut> concerts) {
    final List<ConcertBrut> dedoublonnes = Dedoublonner.dedoublonner(concerts);

    final Map<String, String> tableauResolution = new LinkedHashMap<>();
    final List<String> noms =
        dedoublonnes.stream()
            .filter(WorkerFetchService::estEligibleResolutionDeezer)
            .map(ConcertBrut::artiste)
            .distinct()
            .toList();

    for (final String nom : noms) {
      final ResolveurNomCanonique.ResultatResolution resultat =
          resolveur.resoudreNomCanonique("artiste", nom);
      tableauResolution.put(nom, nomCanoniqueValideOuBrut(nom, resultat.nom()));
    }

    return dedoublonnes.stream()
        .map(
            c -> {
              if (!estEligibleResolutionDeezer(c)) {
                return c;
              }
              final String nomResolu = tableauResolution.getOrDefault(c.artiste(), c.artiste());
              if (nomResolu.equals(c.artiste())) {
                return c;
              }
              return new ConcertBrut(
                  nomResolu,
                  c.date(),
                  c.heure(),
                  c.salle(),
                  c.ville(),
                  c.dept(),
                  c.url(),
                  c.source(),
                  c.classification(),
                  c.genre(),
                  c.statut(),
                  c.presaleDebut(),
                  c.presaleNom(),
                  c.salleSourceId(),
                  c.artisteSourceId());
            })
        .toList();
  }

  private void sauvegarderQuota() {
    final QuotaTM quota = tm.obtenirQuota();
    if (quota != null) {
      gestionQuotaTM.ecrireQuota(quota);
    }
  }

  private void attendreRateLimit() {
    try {
      Thread.sleep(DELAI_ENTRE_ARTISTES_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
