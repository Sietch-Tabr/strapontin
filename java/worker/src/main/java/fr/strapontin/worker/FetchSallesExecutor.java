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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
   * @param identifiantSalle identifiant préfixé {@code source:identifiant_externe}
   *     et libellé du lieu chez la source
   * @param destination liste dans laquelle ajouter les concerts trouvés
   * @param horizons reçoit, par source interrogée avec succès, la dernière
   *     date qu'elle couvre
   * @return {@code false} si une source n'a pas répondu (panne réseau)
   */
  private boolean fetchEtSuivreSalle(
      final IdentifiantSalle identifiantSalle,
      final List<ConcertBrut> destination,
      final Map<String, LocalDate> horizons) {
    final String identifiant = identifiantSalle.identifiant();
    boolean joignable = true;
    for (final SourceConcerts source : sources) {
      if (source.accepteIdentifiantSalle(identifiant)) {
        try {
          final List<ConcertBrut> resultat =
              source.fetchSalle(identifiant, identifiantSalle.libelle());
          destination.addAll(resultat);
          rapportage
              .suiviSanteSourcesService()
              .enregistrerResultatSalle(identifiant, resultat.size());
          final LocalDate horizon = source.horizonSalle();
          if (horizon != null) {
            horizons.put(source.nom(), horizon);
          }
        } catch (Exception e) {
          LOG.error(
              "{} fetchSalle échoué identifiant={} : {}",
              source.nom(),
              identifiant,
              e.getMessage());
          if (WorkerFetchCommun.estEchecReseauTransitoire(e)) {
            joignable = false;
          }
        }
      }
    }
    return joignable;
  }

  /**
   * Fetch les concerts d'une ou plusieurs salles par identifiants
   * préfixés séparés par une virgule (ex. "tm:id1,eventim:internal:accor arena").
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
      final String id = identifiant.trim();
      fetchEtSuivreSalle(new IdentifiantSalle(id, libelleSource(id)), concerts, new HashMap<>());
    }
    commun.sauvegarderQuota();

    final List<ConcertBrut> dedoublonnes = commun.resoudreEtDeduplicer(concerts, LOG);
    LOG.info(
        "fetch:salle identifiants={} bruts={} dédoublonnés={}",
        identifiantsStr,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    commun.enregistrerLieuxEventim();
    recalculerMatchesService.recalculerMatches(null);
    LOG.info("fetch:salle terminé identifiants={}", identifiantsStr);
  }

  // Seul Eventim s'interroge par libellé (aucun identifiant de lieu exposé).
  private String libelleSource(final String identifiantPrefixe) {
    final int sep = identifiantPrefixe.indexOf(':');
    if (sep <= 0) {
      return null;
    }
    final List<String> libelles =
        commun
            .obtenirJdbc()
            .queryForList(
                "SELECT libelle_source FROM salle_sources"
                    + " WHERE source = :source AND identifiant_externe = :identifiant",
                Map.of(
                    "source", identifiantPrefixe.substring(0, sep),
                    "identifiant", identifiantPrefixe.substring(sep + 1)),
                String.class);
    return libelles.isEmpty() ? null : libelles.get(0);
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
   * Identifiant préfixé d'une salle chez une source, avec le libellé du lieu
   * chez cette source quand elle s'interroge par libellé (Eventim).
   *
   * @param identifiant identifiant préfixé {@code source:identifiant_externe}
   * @param libelle libellé du lieu chez la source, ou null
   */
  private record IdentifiantSalle(String identifiant, String libelle) {}

  /**
   * Salle suivie à refetcher, avec ses identifiants toutes sources confondues.
   *
   * @param salleId UUID de la salle (texte), pour le suivi d'absences après
   *     persistance
   * @param identifiants identifiants de la salle chez chaque source
   */
  private record SalleAFetcher(String salleId, List<IdentifiantSalle> identifiants) {}

  // Le préfixe émis ici doit correspondre à celui attendu par
  // TicketmasterSource.accepteIdentifiantSalle ("tm", pas la valeur canonique
  // "ticketmaster" de salle_sources.source) — sans cette traduction, fetchSalle
  // n'est jamais invoqué pour aucune salle TM (mismatch silencieux, aucune
  // exception, aucun log d'erreur). Un lieu Eventim sans libellé connu ne peut
  // pas être interrogé. Restreint aux salles suivies (user_salles) — voir
  // Javadoc de executerToutes.
  private List<SalleAFetcher> recupererIdentifiantsSallesSuivies(
      final List<String> salleIdsCibles) {
    final boolean filtre = salleIdsCibles != null;
    final Map<String, List<IdentifiantSalle>> parSalle = new LinkedHashMap<>();
    commun
        .obtenirJdbc()
        .queryForList(
            "SELECT ss.salle_id::text AS salle_id, ss.source AS source,"
                + " ss.identifiant_externe AS identifiant, ss.libelle_source AS libelle"
                + " FROM salle_sources ss"
                + " WHERE EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = ss.salle_id)"
                + " AND (ss.source <> 'eventim' OR ss.libelle_source IS NOT NULL)"
                + (filtre ? " AND ss.salle_id::text IN (:ids)" : "")
                + " ORDER BY ss.salle_id, ss.source",
            filtre ? Map.of("ids", salleIdsCibles) : Map.of())
        .forEach(
            ligne -> {
              final String source = (String) ligne.get("source");
              final String prefixe =
                  "ticketmaster".equals(source) ? TicketmasterSource.PREFIXE_IDENTIFIANT : source;
              parSalle
                  .computeIfAbsent((String) ligne.get("salle_id"), _ -> new ArrayList<>())
                  .add(
                      new IdentifiantSalle(
                          prefixe + ":" + ligne.get("identifiant"), (String) ligne.get("libelle")));
            });
    return parSalle.entrySet().stream()
        .map(entree -> new SalleAFetcher(entree.getKey(), entree.getValue()))
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
        final ResultatLotSalles resultatLot = traiterLotSalles(lotSalles);
        resultat = WorkerFetchCommun.accumuler(resultat, resultatLot.persistance());
        if (!resultatLot.sallesInjoignables().isEmpty()) {
          echecsLots.add("lot " + i + "-" + (i + lotSalles.size()) + " : panne réseau transitoire");
          salleIdsEnEchec.addAll(resultatLot.sallesInjoignables());
        }
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
   * Bilan d'un lot de salles.
   *
   * @param persistance bilan de persistance
   * @param sallesInjoignables salles dont une source n'a pas répondu
   */
  private record ResultatLotSalles(
      ResultatPersistance persistance, List<String> sallesInjoignables) {}

  /**
   * Fetch + dédoublonnage + persistance d'un lot de salles ({@value
   * TAILLE_LOT_SALLES}) — persistance par lot plutôt qu'en bloc final,
   * chaque chaîne d'identifiants regroupe déjà toutes les sources d'une
   * même salle ({@code GROUP BY ss.salle_id}). Les absences ne sont comptées
   * que pour les salles dont toutes les sources ont répondu.
   * @param lotSalles sous-ensemble de salles suivies
   * @return bilan du lot
   */
  private ResultatLotSalles traiterLotSalles(final List<SalleAFetcher> lotSalles) {
    final List<ConcertBrut> tous = new ArrayList<>();
    final Map<String, Map<String, LocalDate>> horizonsParSalle = new LinkedHashMap<>();
    final List<String> sallesInjoignables = new ArrayList<>();
    for (final SalleAFetcher salle : lotSalles) {
      boolean joignable = true;
      final Map<String, LocalDate> horizons = new HashMap<>();
      for (final IdentifiantSalle identifiant : salle.identifiants()) {
        joignable &= fetchEtSuivreSalle(identifiant, tous, horizons);
      }
      if (joignable) {
        horizonsParSalle.put(salle.salleId(), horizons);
      } else {
        sallesInjoignables.add(salle.salleId());
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
    commun.enregistrerLieuxEventim();
    // Une requête par salle : les sources interrogées (donc les horizons) varient.
    horizonsParSalle.forEach(
        (salleId, horizons) ->
            commun.mettreAJourAbsencesSalles(List.of(salleId), debutLot, horizons));
    return new ResultatLotSalles(resultat, sallesInjoignables);
  }
}
