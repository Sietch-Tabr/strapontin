/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.SourceSiteSalle;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline du job {@code fetch:sites-salles} : lecture du site de chaque salle qui vend hors
 * Ticketmaster et Eventim, dédoublonnage, persistance, absences. Un site à la fois, une fois par
 * jour : un site qui n'a pas répondu en entier ne compte aucune absence.
 * @since 3.0.6
 * @author Kwaite
 */
class FetchSitesSallesExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(FetchSitesSallesExecutor.class);
  private static final String TYPE_JOB = "fetch:sites-salles";
  // Le site liste tout ce qu'il programme : aucune date à venir n'est hors de sa portée.
  private static final int ANNEES_DE_COUVERTURE = 10;

  private final List<SourceSiteSalle> sites;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final FetchRapportage rapportage;
  private final WorkerFetchCommun commun;

  /* package-private : instancié uniquement par WorkerFetchService (même package). */
  FetchSitesSallesExecutor(
      final List<SourceSiteSalle> sites,
      final PersisterConcerts persisterConcerts,
      final RecalculerMatchesService recalculerMatchesService,
      final FetchRapportage rapportage,
      final WorkerFetchCommun commun) {
    this.sites = sites;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.rapportage = rapportage;
    this.commun = commun;
  }

  /* package-private : appelé par WorkerFetchService (même package). */
  void executer() {
    LOG.info("{} démarré", TYPE_JOB);
    rapportage
        .workerStatsService()
        .executerAvecStatsToleretErreur(LOG, TYPE_JOB, this::executerCycle);
  }

  private WorkerStatsService.ResultatJob executerCycle() {
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    final List<String> echecs = new ArrayList<>();
    for (final SourceSiteSalle site : sites) {
      try {
        resultat = WorkerFetchCommun.accumuler(resultat, traiterSite(site));
      } catch (Exception e) {
        LOG.warn("{} {} échoué : {}", TYPE_JOB, site.nom(), e.getMessage());
        echecs.add(site.nom() + " : " + e.getMessage());
      }
    }
    commun.remonterEchecsLots(TYPE_JOB, echecs, sites.size(), List.of());
    recalculerMatchesService.recalculerMatches(null);
    rapportage.notificationDigestService().executerDigest("temps_reel");
    LOG.info("{} terminé", TYPE_JOB);
    return new WorkerStatsService.ResultatJob(
        resultat,
        !sites.isEmpty() && echecs.size() == sites.size() ? "tous les sites ont échoué" : null);
  }

  private ResultatPersistance traiterSite(final SourceSiteSalle site) {
    final List<ConcertBrut> lus = site.lireProgrammation();
    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(lus, LOG);
    LOG.info(
        "{} {} bruts={} dédoublonnés={}", TYPE_JOB, site.nom(), lus.size(), dedoublonnes.size());
    final String debut = horlogeDeLaBase();
    final ResultatPersistance resultat = persisterConcerts.persisterConcerts(dedoublonnes);
    // Lecture complète : un concert que le site n'annonce plus gagne une absence.
    commun.mettreAJourAbsencesSalles(
        sallesDuSite(site.identifiantSite()),
        debut,
        Map.of(SourceSiteSalle.SOURCE, LocalDate.now().plusYears(ANNEES_DE_COUVERTURE)));
    return resultat;
  }

  // Les dates de mise à jour sont celles de la base : l'horloge de la JVM peut en différer de
  // quelques millisecondes, et un concert tout juste écrit passerait pour non revu.
  private String horlogeDeLaBase() {
    return commun
        .obtenirJdbc()
        .queryForObject("SELECT clock_timestamp()::text", Map.of(), String.class);
  }

  // Une salle rattachée au site (« triton ») ou à l'une de ses salles (« allmol:zion park »).
  private List<String> sallesDuSite(final String identifiantSite) {
    return commun
        .obtenirJdbc()
        .queryForList(
            """
            SELECT DISTINCT salle_id::text FROM salle_sources
            WHERE source = :source
              AND (identifiant_externe = :site OR identifiant_externe LIKE :prefixe)
            """,
            Map.of(
                "source",
                SourceSiteSalle.SOURCE,
                "site",
                identifiantSite,
                "prefixe",
                identifiantSite + ":%"),
            String.class);
  }
}
