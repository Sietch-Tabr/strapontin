/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.ResultatRegion;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline complet du job {@code fetch:region} : fetch par département
 * auprès de toutes les sources, dédoublonnage, résolution du nom
 * canonique, persistance par lot, suivi des absences — extrait de
 * {@link WorkerFetchService}.
 * @since 2.9.1
 * @author Kwaite
 */
class FetchRegionExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchRegionExecutor.class);
  private static final int TAILLE_LOT_DEPTS = 5;

  private final List<SourceConcerts> sources;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  /* package-private : instancié uniquement par Spring (même package). */
  FetchRegionExecutor(
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
   * @param deptsCibles départements à retenter, ou {@code null} pour un cycle
   *     complet (tous les départements configurés)
   */
  /* package-private : appelé par WorkerFetchService (même package). */
  void executer(final List<String> deptsCibles) {
    final boolean retry = deptsCibles != null;
    LOG.info("fetch:region démarré{}", retry ? " (retry ciblé)" : "");
    rapportage
        .workerStatsService()
        .executerAvecStatsToleretErreur(
            LOG, "fetch:region", () -> executerCycle(retry, deptsCibles));
  }

  private WorkerStatsService.ResultatJob executerCycle(
      final boolean retry, final List<String> deptsCibles) {
    if (!commun.obtenirGestionQuotaTM().cronAutorise()) {
      LOG.warn("fetch:region suspendu — quota TM insuffisant");
      return WorkerStatsService.ResultatJob.quotaInsuffisant();
    }

    final List<String> deptsListe = retry ? deptsCibles : deptsConfigures();
    if (deptsListe.isEmpty()) {
      LOG.info("fetch:region — aucun département configuré");
      return WorkerStatsService.ResultatJob.succes(new ResultatPersistance(0, 0));
    }

    final ResultatPersistance resultat = traiterLots(deptsListe);
    commun.sauvegarderQuota();
    recalculerMatchesService.recalculerMatches(null);
    rapportage.notificationDigestService().executerDigest("temps_reel");
    LOG.info("fetch:region terminé");
    return WorkerStatsService.ResultatJob.succes(resultat);
  }

  private List<String> deptsConfigures() {
    final List<String[]> allDepts =
        commun
            .obtenirJdbc()
            .query(
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
        final ResultatLotDepartements resultatLot = traiterLotDepartements(lotDepts);
        resultat = WorkerFetchCommun.accumuler(resultat, resultatLot.persistance());
        if (resultatLot.sourceInjoignable()) {
          echecsLots.add("depts=" + lotDepts + " : panne réseau transitoire");
          deptsEnEchec.addAll(lotDepts);
        }
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
   * Fetch + dédoublonnage + persistance d'un lot de départements ({@value
   * TAILLE_LOT_DEPTS}) — met à jour les absences des salles du lot
   * indépendamment de tout suivi utilisateur (détail : CLAUDE.md, Jobs
   * background).
   * @param lotDepts sous-ensemble de départements
   * @return bilan du lot
   */
  private ResultatLotDepartements traiterLotDepartements(final List<String> lotDepts) {
    final List<ConcertBrut> tous = new ArrayList<>();
    final Map<String, LocalDate> horizons = new HashMap<>();
    boolean sourceInjoignable = false;
    for (final SourceConcerts source : sources) {
      try {
        final ResultatRegion resultatSource = source.fetchRegionCouverte(lotDepts, null);
        tous.addAll(resultatSource.concerts());
        if (resultatSource.horizon() != null) {
          horizons.put(source.nom(), resultatSource.horizon());
        }
      } catch (Exception e) {
        LOG.warn("{} fetchRegion échoué depts={} : {}", source.nom(), lotDepts, e.getMessage());
        sourceInjoignable |= WorkerFetchCommun.estEchecReseauTransitoire(e);
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
    rapportage.enregistreurLieuxEventim().enregistrer();
    // Un concert non revu faute de réponse d'une source n'est pas absent.
    if (!sourceInjoignable) {
      commun.mettreAJourAbsencesSalles(sallesConnuesDesDepartements(lotDepts), debutLot, horizons);
    }
    return new ResultatLotDepartements(resultat, sourceInjoignable);
  }

  /**
   * Bilan d'un lot de départements.
   *
   * @param persistance bilan de persistance
   * @param sourceInjoignable {@code true} si une source n'a pas répondu
   */
  private record ResultatLotDepartements(
      ResultatPersistance persistance, boolean sourceInjoignable) {}

  /**
   * Salles déjà connues (concerts futurs déjà en base) pour un lot de
   * départements — inclut les salles non suivies.
   */
  private List<String> sallesConnuesDesDepartements(final List<String> depts) {
    return commun
        .obtenirJdbc()
        .queryForList(
            "SELECT DISTINCT salle_id::text FROM concerts"
                + " WHERE dept IN (:depts) AND salle_id IS NOT NULL AND date >= CURRENT_DATE",
            Map.of("depts", depts),
            String.class);
  }
}
