/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.SourceAvecFetchLot;
import fr.strapontin.sources.SourceConcerts;
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
 * Pipeline complet du job {@code fetch:artistes:batch} : fetch TM par lot d'attractionIds (+
 * fallback keyword) et fetch individuel des autres sources, dédoublonnage, résolution du nom
 * canonique, persistance par lot, suivi des absences.
 *
 * <p>Extrait de {@link WorkerFetchService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class FetchArtistesBatchExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchArtistesBatchExecutor.class);
  private static final int TAILLE_LOT_TM = 20;

  private final List<SourceConcerts> sources;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  FetchArtistesBatchExecutor(
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
   * @param artisteIdsCibles identifiants ({@code artistes.id}, texte) à retenter, ou {@code null}
   *     pour un cycle complet
   */
  void executer(final List<String> artisteIdsCibles) {
    final boolean retry = artisteIdsCibles != null;
    LOG.info("fetch:artistes:batch démarré{}", retry ? " (retry ciblé)" : "");
    rapportage
        .workerStatsService()
        .executerAvecStatsToleretErreur(
            LOG, "fetch:artistes:batch", () -> executerCycle(retry, artisteIdsCibles));
  }

  private WorkerStatsService.ResultatJob executerCycle(
      final boolean retry, final List<String> artisteIdsCibles) {
    if (!commun.obtenirGestionQuotaTM().cronAutorise()) {
      LOG.warn("fetch:artistes:batch suspendu — quota TM insuffisant");
      return new WorkerStatsService.ResultatJob(new ResultatPersistance(0, 0), "quota insuffisant");
    }

    final List<Map<String, Object>> rows = chargerArtistesSuivisBatch(retry, artisteIdsCibles);
    if (rows.isEmpty()) {
      LOG.info("fetch:artistes:batch — aucun artiste suivi");
      return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
    }

    final List<SourceConcerts> sourcesSimples =
        sources.stream().filter(s -> !(s instanceof SourceAvecFetchLot)).toList();
    final ResultatPersistance resultat = traiterLots(rows, sourcesSimples);

    commun.sauvegarderQuota();
    recalculerMatchesService.recalculerMatches(null);
    rapportage.notificationDigestService().executerDigest("temps_reel");
    LOG.info("fetch:artistes:batch terminé");
    return WorkerStatsService.ResultatJob.succes(resultat);
  }

  private List<Map<String, Object>> chargerArtistesSuivisBatch(
      final boolean retry, final List<String> artisteIdsCibles) {
    return commun
        .obtenirJdbc()
        .queryForList(
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
  }

  private ResultatPersistance traiterLots(
      final List<Map<String, Object>> rows, final List<SourceConcerts> sourcesSimples) {
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    final List<String> echecsLots = new ArrayList<>();
    final List<String> idsEnEchec = new ArrayList<>();
    final int totalLots = (rows.size() + TAILLE_LOT_TM - 1) / TAILLE_LOT_TM;
    for (int i = 0; i < rows.size(); i += TAILLE_LOT_TM) {
      final List<Map<String, Object>> lot =
          rows.subList(i, Math.min(i + TAILLE_LOT_TM, rows.size()));
      try {
        resultat = WorkerFetchCommun.accumuler(resultat, traiterLot(lot, sourcesSimples));
      } catch (Exception e) {
        LOG.error(
            "fetch:artistes:batch lot {}-{} échoué : {}", i, i + lot.size(), e.getMessage(), e);
        echecsLots.add("lot " + i + "-" + (i + lot.size()) + " : " + e.getMessage());
        lot.forEach(row -> idsEnEchec.add((String) row.get("artiste_id_str")));
      }
    }
    commun.remonterEchecsLots("fetch:artistes:batch", echecsLots, totalLots, idsEnEchec);
    return resultat;
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
  private ResultatPersistance traiterLot(
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
          LOG.warn("{} fetchArtiste échoué artiste={} : {}", source.nom(), nom, e.getMessage());
        }
        commun.attendreRateLimit();
      }
    }

    final List<ConcertBrut> tous = new ArrayList<>(concertsTM);
    tous.addAll(concertsAutresSources);

    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(tous, LOG);
    LOG.info(
        "fetch:artistes:batch lot={} bruts={} dédoublonnés={}",
        lot.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    final List<String> artisteIds =
        lot.stream().map(row -> (String) row.get("artiste_id_str")).toList();
    commun.mettreAJourAbsences("artiste_id", artisteIds, debutLot);
    return resultat;
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
    LOG.warn(
        "artiste_sources ambigu — {} identifiant_externe ticketmaster distincts pour"
            + " artiste={} artisteId={}, fetch TM ignoré ce cycle",
        nbIdsDistincts,
        nom,
        artisteIdStr);
    commun
        .obtenirJdbc()
        .update(
            "UPDATE artiste_sources SET necessite_intervention_le ="
                + " COALESCE(necessite_intervention_le, now()) WHERE artiste_id = CAST(:artisteId"
                + " AS uuid) AND source = 'ticketmaster'",
            Map.of("artisteId", artisteIdStr));
    return true;
  }

  private String rechercherEtEnregistrerAttractionId(final String nom, final String artisteIdStr) {
    String attractionId = null;
    try {
      attractionId = commun.obtenirTm().rechercherAttractionId(nom);
      if (attractionId != null) {
        commun
            .obtenirJdbc()
            .update(
                "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)"
                    + " VALUES (CAST(:artisteId AS uuid), 'ticketmaster', :attractionId)"
                    + " ON CONFLICT DO NOTHING",
                Map.of("artisteId", artisteIdStr, "attractionId", attractionId));
      }
    } catch (Exception e) {
      LOG.warn("rechercherAttractionId échoué artiste={} : {}", nom, e.getMessage());
    }
    // Throttle nécessaire ici : un lot avec plusieurs artistes jamais résolus peut générer une
    // rafale d'appels /attractions.json sans délai sinon.
    commun.attendreRateLimit();
    return attractionId;
  }

  private List<ConcertBrut> fetchParLotsAttractionIds(final List<String> attractionIds) {
    final List<ConcertBrut> resultats = new ArrayList<>();
    for (int i = 0; i < attractionIds.size(); i += TAILLE_LOT_TM) {
      final List<String> lot =
          attractionIds.subList(i, Math.min(i + TAILLE_LOT_TM, attractionIds.size()));
      try {
        final List<ConcertBrut> concertsLot = commun.obtenirTm().fetchArtistesLot(lot);
        resultats.addAll(concertsLot);
        rapportage.suiviSanteSourcesService().enregistrerResultatArtistesLot(lot, concertsLot);
      } catch (Exception e) {
        LOG.warn("TM fetchArtistesLot lot={} échoué : {}", lot.size(), e.getMessage());
      }
    }
    return resultats;
  }

  private List<ConcertBrut> fetchParNomsSansAttractionId(final List<String> nomsSansTmId) {
    final List<ConcertBrut> resultats = new ArrayList<>();
    for (final String nom : nomsSansTmId) {
      try {
        resultats.addAll(commun.obtenirTm().fetchArtiste(nom));
      } catch (Exception e) {
        LOG.warn("TM fetchArtiste échoué artiste={} : {}", nom, e.getMessage());
      }
      commun.attendreRateLimit();
    }
    return resultats;
  }
}
