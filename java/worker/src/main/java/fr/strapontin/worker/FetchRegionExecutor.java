/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Pipeline complet du job {@code fetch:region} : fetch par département auprès de toutes les
 * sources, dédoublonnage, résolution du nom canonique, persistance par lot, suivi des absences des
 * salles connues du lot de départements traité.
 *
 * <p>Extrait de {@link WorkerFetchService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class FetchRegionExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchRegionExecutor.class);
  private static final int TAILLE_LOT_DEPTS = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceConcerts> sources;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NotificationDigestService notificationDigestService;
  private final WorkerStatsService workerStatsService;
  private final WorkerFetchCommun commun;

  FetchRegionExecutor(
      final NamedParameterJdbcTemplate jdbc,
      final List<SourceConcerts> sources,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final GestionQuotaTM gestionQuotaTM,
      final NotificationDigestService notificationDigestService,
      final WorkerStatsService workerStatsService,
      final WorkerFetchCommun commun) {
    this.jdbc = jdbc;
    this.sources = sources;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.notificationDigestService = notificationDigestService;
    this.workerStatsService = workerStatsService;
    this.commun = commun;
  }

  /**
   * @param deptsCibles départements à retenter, ou {@code null} pour un cycle complet (tous les
   *     départements configurés)
   */
  void executer(final List<String> deptsCibles) {
    final boolean retry = deptsCibles != null;
    LOG.info("fetch:region démarré{}", retry ? " (retry ciblé)" : "");
    workerStatsService.executerAvecStatsToleretErreur(
        LOG, "fetch:region", () -> executerCycle(retry, deptsCibles));
  }

  private WorkerStatsService.ResultatJob executerCycle(
      final boolean retry, final List<String> deptsCibles) {
    if (!gestionQuotaTM.cronAutorise()) {
      LOG.warn("fetch:region suspendu — quota TM insuffisant");
      return new WorkerStatsService.ResultatJob(new ResultatPersistance(0, 0), "quota insuffisant");
    }

    final List<String> deptsListe = retry ? deptsCibles : deptsConfigures();
    if (deptsListe.isEmpty()) {
      LOG.info("fetch:region — aucun département configuré");
      return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
    }

    final ResultatPersistance resultat = traiterLots(deptsListe);
    commun.sauvegarderQuota();
    recalculerMatchesService.recalculerMatches(null);
    notificationDigestService.executerDigest("temps_reel");
    LOG.info("fetch:region terminé");
    return WorkerStatsService.ResultatJob.succes(resultat);
  }

  private List<String> deptsConfigures() {
    final List<String[]> allDepts =
        jdbc.query(
            "SELECT departements FROM user_config",
            Map.of(),
            (rs, _) -> (String[]) rs.getArray("departements").getArray());
    return new ArrayList<>(collecterTousLesDepts(allDepts));
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

  private ResultatPersistance traiterLots(final List<String> deptsListe) {
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    final List<String> echecsLots = new ArrayList<>();
    final List<String> deptsEnEchec = new ArrayList<>();
    final int totalLots = (deptsListe.size() + TAILLE_LOT_DEPTS - 1) / TAILLE_LOT_DEPTS;
    for (int i = 0; i < deptsListe.size(); i += TAILLE_LOT_DEPTS) {
      final List<String> lotDepts =
          deptsListe.subList(i, Math.min(i + TAILLE_LOT_DEPTS, deptsListe.size()));
      try {
        resultat = WorkerFetchCommun.accumuler(resultat, traiterLotDepartements(lotDepts));
      } catch (Exception e) {
        LOG.error("fetch:region lot {} échoué : {}", lotDepts, e.getMessage(), e);
        echecsLots.add("depts=" + lotDepts + " : " + e.getMessage());
        deptsEnEchec.addAll(lotDepts);
      }
    }
    commun.remonterEchecsLots("fetch:region", echecsLots, totalLots, deptsEnEchec);
    return resultat;
  }

  /**
   * Fetch + dédoublonnage + persistance d'un lot de départements (taille {@value
   * TAILLE_LOT_DEPTS}).
   *
   * <p>Persistance par lot plutôt qu'en un seul bloc final : le dédoublonnage cross-sources reste
   * correct à cette granularité, un même concert n'étant jamais rapporté par deux sources dans deux
   * départements différents.
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
        LOG.warn("{} fetchRegion échoué depts={} : {}", source.nom(), lotDepts, e.getMessage());
      }
    }

    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(tous, LOG);
    LOG.info(
        "fetch:region depts={} bruts={} dédoublonnés={}",
        lotDepts.size(),
        tous.size(),
        dedoublonnes.size());

    final String debutLot = OffsetDateTime.now().toString();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    commun.mettreAJourAbsences("salle_id", sallesConnuesDesDepartements(lotDepts), debutLot);
    return resultat;
  }

  /**
   * Salles déjà connues (concerts futurs déjà en base) pour un lot de départements — inclut les
   * salles non suivies.
   */
  private List<String> sallesConnuesDesDepartements(final List<String> depts) {
    return jdbc.queryForList(
        "SELECT DISTINCT salle_id::text FROM concerts"
            + " WHERE dept IN (:depts) AND salle_id IS NOT NULL AND date >= CURRENT_DATE",
        Map.of("depts", depts),
        String.class);
  }
}
