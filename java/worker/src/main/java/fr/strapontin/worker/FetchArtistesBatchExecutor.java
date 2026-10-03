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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline complet du job {@code fetch:artistes:batch} : fetch TM par
 * lot d'attractionIds (+ fallback keyword), fetch individuel des
 * autres sources, dédoublonnage, résolution du nom canonique,
 * persistance par lot, suivi des absences.
 * @since 2.9.1
 * @author Kwaite
 */
class FetchArtistesBatchExecutor {

  // Séparateur des alias dans array_to_string, absent de tout nom réel.
  private static final String SEPARATEUR_ALIAS = "\u001f";
  private static final Logger LOG = LoggerFactory.getLogger(FetchArtistesBatchExecutor.class);
  private static final int TAILLE_LOT_TM = 20;
  private static final int NB_IDS_DISTINCTS_NON_AMBIGU = 1;
  private static final String CLE_ARTISTE_ID_STR = "artiste_id_str";

  private final List<SourceConcerts> sources;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  /* package-private : instancié uniquement par Spring (même package). */
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
   * @param artisteIdsCibles identifiants ({@code artistes.id}, texte) à
   *     retenter, ou {@code null} pour un cycle complet
   */
  /* package-private : appelé par WorkerFetchService (même package). */
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
    // Quota Ticketmaster bas : seule cette source est suspendue, pas Eventim.
    final boolean ticketmasterAutorise = commun.obtenirGestionQuotaTM().cronAutorise();

    final List<Map<String, Object>> rows = chargerArtistesSuivisBatch(retry, artisteIdsCibles);
    if (rows.isEmpty()) {
      LOG.info("fetch:artistes:batch — aucun artiste suivi");
      return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
    }

    final List<SourceConcerts> sourcesSimples =
        sources.stream().filter(s -> !(s instanceof SourceAvecFetchLot)).toList();
    final ResultatPersistance resultat = traiterLots(rows, sourcesSimples, ticketmasterAutorise);

    commun.sauvegarderQuota();
    recalculerMatchesService.recalculerMatches(null);
    rapportage.notificationDigestService().executerDigest("temps_reel");
    LOG.info("fetch:artistes:batch terminé");
    return WorkerStatsService.ResultatJob.selonQuota(resultat, ticketmasterAutorise);
  }

  private List<Map<String, Object>> chargerArtistesSuivisBatch(
      final boolean retry, final List<String> artisteIdsCibles) {
    return commun
        .obtenirJdbc()
        .queryForList(
            "SELECT ua.artiste_id::text AS artiste_id_str, MAX(ua.artiste_affiche) AS"
                + " artiste_affiche, MAX(asm.identifiant_externe) AS attraction_id, COUNT(DISTINCT"
                + " asm.identifiant_externe) AS nb_ids_distincts, MAX(a.deezer_id) AS deezer_id,"
                + " MAX(a.wikidata_id) AS wikidata_id, a.nom_affiche AS nom_affiche,"
                + " array_to_string(a.alias_wikidata, E'\\x1f') AS alias_wikidata FROM"
                + " user_artistes ua JOIN artistes a ON a.id = ua.artiste_id LEFT JOIN"
                + " artiste_sources asm   ON asm.artiste_id = ua.artiste_id AND asm.source ="
                + " 'ticketmaster'"
                + (retry ? " WHERE ua.artiste_id::text IN (:ids)" : "")
                + " GROUP BY ua.artiste_id, a.id",
            retry ? Map.of("ids", artisteIdsCibles) : Map.of());
  }

  private ResultatPersistance traiterLots(
      final List<Map<String, Object>> rows,
      final List<SourceConcerts> sourcesSimples,
      final boolean ticketmasterAutorise) {
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    final List<String> echecsLots = new ArrayList<>();
    final List<String> idsEnEchec = new ArrayList<>();
    final int totalLots = (rows.size() + TAILLE_LOT_TM - 1) / TAILLE_LOT_TM;
    for (int i = 0; i < rows.size(); i += TAILLE_LOT_TM) {
      final List<Map<String, Object>> lot =
          rows.subList(i, Math.min(i + TAILLE_LOT_TM, rows.size()));
      try {
        final ResultatLot resultatLot = traiterLot(lot, sourcesSimples, ticketmasterAutorise);
        resultat = WorkerFetchCommun.accumuler(resultat, resultatLot.persistance());
        if (resultatLot.echecReseauTransitoire()) {
          LOG.warn(
              "fetch:artistes:batch lot {}-{} dégradé par une panne réseau transitoire",
              i,
              i + lot.size());
          echecsLots.add("lot " + i + "-" + (i + lot.size()) + " : panne réseau transitoire");
          lot.forEach(row -> idsEnEchec.add((String) row.get(CLE_ARTISTE_ID_STR)));
        }
      } catch (Exception e) {
        LOG.error(
            "fetch:artistes:batch lot {}-{} échoué : {}", i, i + lot.size(), e.getMessage(), e);
        echecsLots.add("lot " + i + "-" + (i + lot.size()) + " : " + e.getMessage());
        lot.forEach(row -> idsEnEchec.add((String) row.get(CLE_ARTISTE_ID_STR)));
      }
    }
    commun.remonterEchecsLots("fetch:artistes:batch", echecsLots, totalLots, idsEnEchec);
    return resultat;
  }

  /**
   * Bilan d'un lot, avec le signal d'échec réseau transitoire éventuellement
   * absorbé lors du fetch (DNS, I/O, timeout) — distinct d'une exception non
   * rattrapée, qui échoue déjà tout le lot via le {@code catch} appelant.
   */
  private record ResultatLot(ResultatPersistance persistance, boolean echecReseauTransitoire) {}

  /**
   * Fetch + dédoublonnage + persistance d'un lot ({@value TAILLE_LOT_TM})
   * — persistance par lot pour ne pas perdre le quota TM déjà consommé.
   * @param lot sous-ensemble de lignes artiste
   * @param sourcesSimples sources interrogées artiste par artiste
   * @param ticketmasterAutorise {@code false} si le quota Ticketmaster est bas
   * @return bilan de persistance du lot
   */
  private ResultatLot traiterLot(
      final List<Map<String, Object>> lot,
      final List<SourceConcerts> sourcesSimples,
      final boolean ticketmasterAutorise) {
    final AtomicBoolean echecReseau = new AtomicBoolean(false);
    final List<ConcertBrut> concertsTM =
        ticketmasterAutorise ? fetchTMParArtistes(lot, echecReseau) : List.of();

    final List<ConcertBrut> concertsAutresSources = new ArrayList<>();
    for (final SourceConcerts source : sourcesSimples) {
      for (final Map<String, Object> row : lot) {
        final String nom = (String) row.get("artiste_affiche");
        final String deezerId = (String) row.get("deezer_id");
        final String wikidataId = (String) row.get("wikidata_id");
        try {
          concertsAutresSources.addAll(
              source.fetchArtiste(nom, deezerId, wikidataId, autresNomsConnus(row)));
        } catch (Exception e) {
          LOG.warn("{} fetchArtiste échoué artiste={} : {}", source.nom(), nom, e.getMessage());
          if (WorkerFetchCommun.estEchecReseauTransitoire(e)) {
            echecReseau.set(true);
          }
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
    rapportage.enregistreurLieuxEventim().enregistrer();
    // Un concert non revu faute de réponse ou d'interrogation de la source
    // (Ticketmaster sauté) n'est pas absent : le comptage par artiste ne
    // distingue pas les sources.
    if (!echecReseau.get() && ticketmasterAutorise) {
      final List<String> artisteIds =
          lot.stream().map(row -> (String) row.get(CLE_ARTISTE_ID_STR)).toList();
      commun.mettreAJourAbsencesArtistes(artisteIds, debutLot);
    }
    return new ResultatLot(resultat, echecReseau.get());
  }

  /**
   * Fetch TM pour une liste d'artistes suivis — résout les
   * attractionId manquants puis fetch groupé (÷20 le quota), fallback
   * keyword pour les artistes sans attractionId TM.
   * @param rows lignes agrégées par artiste_id (artiste_affiche,
   *     artiste_id_str, attraction_id, nb_ids_distincts)
   * @return concerts bruts collectés depuis TM
   */
  private record ResolutionAttractionIds(List<String> attractionIds, List<String> nomsSansTmId) {}

  private List<ConcertBrut> fetchTMParArtistes(
      final List<Map<String, Object>> rows, final AtomicBoolean echecReseau) {
    final ResolutionAttractionIds resolution = resoudreAttractionIds(rows);
    final List<ConcertBrut> resultats =
        new ArrayList<>(fetchParLotsAttractionIds(resolution.attractionIds(), echecReseau));
    resultats.addAll(fetchParNomsSansAttractionId(resolution.nomsSansTmId(), echecReseau));
    return resultats;
  }

  // artiste_sources n'a pas de contrainte d'unicité sur artiste_id (PK =
  // (source, identifiant_externe)) — une fusion mal dédupliquée peut laisser
  // deux identifiants TM pour le même artiste, rendant le MAX() de la requête
  // appelante arbitraire. Mieux vaut ne pas fetcher avec un identifiant
  // potentiellement faux que de rapatrier silencieusement les concerts d'un
  // autre artiste sous cette identité.
  private ResolutionAttractionIds resoudreAttractionIds(final List<Map<String, Object>> rows) {
    final List<String> attractionIds = new ArrayList<>();
    final List<String> nomsSansTmId = new ArrayList<>();

    for (final Map<String, Object> row : rows) {
      final String nom = (String) row.get("artiste_affiche");
      final String artisteIdStr = (String) row.get(CLE_ARTISTE_ID_STR);
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
    if (nbIdsDistincts <= NB_IDS_DISTINCTS_NON_AMBIGU) {
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
    // Throttle nécessaire ici : un lot avec plusieurs artistes jamais résolus
    // peut générer une rafale d'appels /attractions.json sans délai sinon.
    commun.attendreRateLimit();
    return attractionId;
  }

  private List<ConcertBrut> fetchParLotsAttractionIds(
      final List<String> attractionIds, final AtomicBoolean echecReseau) {
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
        if (WorkerFetchCommun.estEchecReseauTransitoire(e)) {
          echecReseau.set(true);
        }
      }
    }
    return resultats;
  }

  private List<ConcertBrut> fetchParNomsSansAttractionId(
      final List<String> nomsSansTmId, final AtomicBoolean echecReseau) {
    final List<ConcertBrut> resultats = new ArrayList<>();
    for (final String nom : nomsSansTmId) {
      try {
        resultats.addAll(commun.obtenirTm().fetchArtiste(nom));
      } catch (Exception e) {
        LOG.warn("TM fetchArtiste échoué artiste={} : {}", nom, e.getMessage());
        if (WorkerFetchCommun.estEchecReseauTransitoire(e)) {
          echecReseau.set(true);
        }
      }
      commun.attendreRateLimit();
    }
    return resultats;
  }

  // Libellé et alias Wikidata : reconnaissent un nom de scène publié sous une
  // forme longue par une source (« -M- Matthieu Chedid »).
  private static List<String> autresNomsConnus(final Map<String, Object> row) {
    final List<String> noms = new ArrayList<>();
    if (row.get("nom_affiche") instanceof String nomAffiche) {
      noms.add(nomAffiche);
    }
    if (row.get("alias_wikidata") instanceof String alias && !alias.isEmpty()) {
      noms.addAll(List.of(alias.split(SEPARATEUR_ALIAS)));
    }
    return noms;
  }
}
