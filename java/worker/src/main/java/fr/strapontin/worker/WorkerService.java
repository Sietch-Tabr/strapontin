/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceAvecFetchLot;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.ReconciliationNocturneService;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Orchestration des jobs : fetch → dédoublonnage → résolution → persistance → recalcul.
///
/// Délégué par les jobs Quartz du package `jobs`. Chaque méthode correspond
/// à un type de job BullMQ porté en Java.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class WorkerService {

  private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

  private static final int DELAI_ENTRE_ARTISTES_MS = 250;
  private static final int TAILLE_LOT_TM = 20;
  private static final int TAILLE_LOT_DEPTS = 5;
  private static final int TAILLE_LOT_SALLES = 20;

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final EventimSource eventim;
  private final ResolveurNomCanonique resolveur;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NotificationDigestService notificationDigestService;
  private final NamedParameterJdbcTemplate jdbc;
  private final SuiviSanteSourcesService suiviSanteSourcesService;
  private final ReconciliationNocturneService reconciliationNocturneService;
  private final LogsAdminWorker logsAdminWorker;

  public WorkerService(
      List<SourceConcerts> sources,
      TicketmasterSource tm,
      EventimSource eventim,
      ResolveurNomCanonique resolveur,
      PersisterConcerts persisterConcerts,
      RecalculerMatchesService recalculerMatchesService,
      GestionQuotaTM gestionQuotaTM,
      NotificationDigestService notificationDigestService,
      NamedParameterJdbcTemplate jdbc,
      SuiviSanteSourcesService suiviSanteSourcesService,
      ReconciliationNocturneService reconciliationNocturneService,
      LogsAdminWorker logsAdminWorker) {
    this.sources = sources;
    this.tm = tm;
    this.eventim = eventim;
    this.resolveur = resolveur;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.notificationDigestService = notificationDigestService;
    this.jdbc = jdbc;
    this.suiviSanteSourcesService = suiviSanteSourcesService;
    this.reconciliationNocturneService = reconciliationNocturneService;
    this.logsAdminWorker = logsAdminWorker;
  }

  /// Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
  ///
  /// Suspendu si le quota TM est inférieur à 500 (SEUIL_QUOTA_CRON). Une exception dans un lot
  /// n'interrompt pas les lots suivants (#792) : le recalcul de matchs et le digest de
  /// notification s'exécutent toujours sur la base des lots effectivement réussis, plutôt que
  /// d'être sautés en cas d'échec partiel.
  public void executerFetchArtistesBatch() {
    log.info("fetch:artistes:batch démarré");
    long debut = System.currentTimeMillis();
    String erreur = null;
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    try {
      if (!gestionQuotaTM.cronAutorise()) {
        log.warn("fetch:artistes:batch suspendu — quota TM insuffisant");
        erreur = "quota insuffisant";
        return;
      }

      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "SELECT ua.artiste_id::text AS artiste_id_str,"
                  + " MAX(ua.artiste_affiche) AS artiste_affiche,"
                  + " MAX(asm.identifiant_externe) AS attraction_id"
                  + " FROM user_artistes ua"
                  + " LEFT JOIN artiste_sources asm"
                  + "   ON asm.artiste_id = ua.artiste_id AND asm.source = 'ticketmaster'"
                  + " GROUP BY ua.artiste_id",
              Map.of());

      if (rows.isEmpty()) {
        log.info("fetch:artistes:batch — aucun artiste suivi");
        return;
      }

      List<SourceConcerts> sourcesSimples =
          sources.stream().filter(s -> !(s instanceof SourceAvecFetchLot)).toList();

      for (int i = 0; i < rows.size(); i += TAILLE_LOT_TM) {
        List<Map<String, Object>> lot = rows.subList(i, Math.min(i + TAILLE_LOT_TM, rows.size()));
        try {
          resultat = accumuler(resultat, traiterLotArtistesBatch(lot, sourcesSimples));
        } catch (Exception e) {
          log.error(
              "fetch:artistes:batch lot {}-{} échoué : {}", i, i + lot.size(), e.getMessage(), e);
        }
      }
      sauvegarderQuota();
      recalculerMatchesService.recalculerMatches(null);
      notificationDigestService.executerDigest("temps_reel");
      log.info("fetch:artistes:batch terminé");
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("fetch:artistes:batch échoué", e);
    } finally {
      enregistrerStatJob(
          "fetch:artistes:batch", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Fetch + dédoublonnage + persistance d'un lot d'artistes (taille {@value TAILLE_LOT_TM}).
  ///
  /// <p>Persistance par lot plutôt qu'en un seul bloc final : sans cela, une interruption
  /// (redéploiement, crash) avant la fin du cycle complet perdrait tout le travail de fetch déjà
  /// effectué sur ce cycle (quota TM consommé pour rien). Le dédoublonnage cross-sources
  /// (TM/Eventim) reste correct à cette granularité : un même concert n'est jamais rapporté par
  /// deux sources pour deux artistes différents, la comparaison par lot suffit.
  ///
  /// @param lot            sous-ensemble de lignes artiste (taille {@value TAILLE_LOT_TM})
  /// @param sourcesSimples sources hors {@link SourceAvecFetchLot} (fetch individuel par artiste)
  /// @return bilan de persistance du lot
  private ResultatPersistance traiterLotArtistesBatch(
      List<Map<String, Object>> lot, List<SourceConcerts> sourcesSimples) {
    List<ConcertBrut> concertsTM = fetchTMParArtistes(lot);

    List<ConcertBrut> concertsAutresSources = new ArrayList<>();
    for (SourceConcerts source : sourcesSimples) {
      for (Map<String, Object> row : lot) {
        String nom = (String) row.get("artiste_affiche");
        try {
          concertsAutresSources.addAll(source.fetchArtiste(nom));
        } catch (Exception e) {
          log.warn("{} fetchArtiste échoué artiste={} : {}", source.nom(), nom, e.getMessage());
        }
        attendreRateLimit();
      }
    }

    List<ConcertBrut> tous = new ArrayList<>(concertsTM);
    tous.addAll(concertsAutresSources);

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:artistes:batch lot={} bruts={} dédoublonnés={}",
        lot.size(),
        tous.size(),
        dedoublonnes.size());

    return persisterConcerts.persisterConcerts(dedoublonnes);
  }

  private static ResultatPersistance accumuler(ResultatPersistance a, ResultatPersistance b) {
    return new ResultatPersistance(a.nbInseres() + b.nbInseres(), a.nbMisAJour() + b.nbMisAJour());
  }

  /// Fetch TM pour une liste d'artistes suivis.
  ///
  /// <p>Deux passes :
  ///
  /// <ol>
  ///   <li>Résolution des {@code attractionId} manquants (1 requête /attractions.json par artiste
  ///       non résolu), puis fetch groupé par lots de {@value TAILLE_LOT_TM} via
  ///       {@code fetchArtistesBatch} — réduit la consommation de quota d'un facteur ×20 par
  ///       rapport à un fetch artiste-par-artiste.
  ///   <li>Fallback keyword ({@code fetchArtiste}) pour les artistes sans {@code attractionId} TM.
  /// </ol>
  ///
  /// @param rows lignes agrégées par {@code artiste_id} avec {@code artiste_affiche},
  ///     {@code artiste_id_str}, {@code attraction_id}
  /// @return concerts bruts collectés depuis TM
  /// Fetch une salle auprès de la source qui accepte son identifiant et enregistre le résultat
  /// auprès de {@link SuiviSanteSourcesService} (#706 phase 1).
  ///
  /// @param identifiant identifiant préfixé {@code source:identifiant_externe}
  /// @param destination liste dans laquelle ajouter les concerts trouvés
  private void fetchEtSuivreSalle(String identifiant, List<ConcertBrut> destination) {
    for (SourceConcerts source : sources) {
      if (source.accepteIdentifiantSalle(identifiant)) {
        try {
          List<ConcertBrut> resultat = source.fetchSalle(identifiant);
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

  private List<ConcertBrut> fetchTMParArtistes(List<Map<String, Object>> rows) {
    List<ConcertBrut> resultats = new ArrayList<>();
    List<String> attractionIds = new ArrayList<>();
    List<String> nomsSansTmId = new ArrayList<>();

    for (Map<String, Object> row : rows) {
      String nom = (String) row.get("artiste_affiche");
      String artisteIdStr = (String) row.get("artiste_id_str");
      String attractionId = (String) row.get("attraction_id");
      if (attractionId == null) {
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
        // #789 : throttle manquant ici jusqu'alors — un lot avec plusieurs artistes jamais
        // résolus pouvait générer une rafale d'appels /attractions.json sans délai.
        attendreRateLimit();
      }
      if (attractionId != null) {
        attractionIds.add(attractionId);
      } else {
        nomsSansTmId.add(nom);
      }
    }

    for (int i = 0; i < attractionIds.size(); i += TAILLE_LOT_TM) {
      List<String> lot =
          attractionIds.subList(i, Math.min(i + TAILLE_LOT_TM, attractionIds.size()));
      try {
        List<ConcertBrut> concertsLot = tm.fetchArtistesLot(lot);
        resultats.addAll(concertsLot);
        suiviSanteSourcesService.enregistrerResultatArtistesLot(lot, concertsLot);
      } catch (Exception e) {
        log.warn("TM fetchArtistesLot lot={} échoué : {}", lot.size(), e.getMessage());
      }
    }

    for (String nom : nomsSansTmId) {
      try {
        resultats.addAll(tm.fetchArtiste(nom));
      } catch (Exception e) {
        log.warn("TM fetchArtiste échoué artiste={} : {}", nom, e.getMessage());
      }
      attendreRateLimit();
    }

    return resultats;
  }

  /// Fetch par région pour tous les départements configurés.
  ///
  /// Suspendu si le quota TM est insuffisant.
  public void executerFetchRegion() {
    log.info("fetch:region démarré");
    long debut = System.currentTimeMillis();
    String erreur = null;
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    try {
      if (!gestionQuotaTM.cronAutorise()) {
        log.warn("fetch:region suspendu — quota TM insuffisant");
        erreur = "quota insuffisant";
        return;
      }

      List<String[]> allDepts =
          jdbc.query(
              "SELECT departements FROM user_config",
              Map.of(),
              (rs, _) -> (String[]) rs.getArray("departements").getArray());

      java.util.Set<String> tousLesDepts = collecterTousLesDepts(allDepts);

      if (tousLesDepts.isEmpty()) {
        log.info("fetch:region — aucun département configuré");
        return;
      }

      List<String> deptsListe = new ArrayList<>(tousLesDepts);
      for (int i = 0; i < deptsListe.size(); i += TAILLE_LOT_DEPTS) {
        List<String> lotDepts =
            deptsListe.subList(i, Math.min(i + TAILLE_LOT_DEPTS, deptsListe.size()));
        try {
          resultat = accumuler(resultat, traiterLotDepartements(lotDepts));
        } catch (Exception e) {
          log.error("fetch:region lot {} échoué : {}", lotDepts, e.getMessage(), e);
        }
      }
      sauvegarderQuota();
      recalculerMatchesService.recalculerMatches(null);
      notificationDigestService.executerDigest("temps_reel");
      log.info("fetch:region terminé");
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("fetch:region échoué", e);
    } finally {
      enregistrerStatJob("fetch:region", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Fetch + dédoublonnage + persistance d'un lot de départements (taille
  /// {@value TAILLE_LOT_DEPTS}).
  ///
  /// <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que
  /// {@link #traiterLotArtistesBatch}) : le dédoublonnage cross-sources reste correct à cette
  /// granularité, un même concert n'étant jamais rapporté par deux sources dans deux départements
  /// différents.
  ///
  /// @param lotDepts sous-ensemble de départements (taille {@value TAILLE_LOT_DEPTS})
  /// @return bilan de persistance du lot
  private ResultatPersistance traiterLotDepartements(List<String> lotDepts) {
    List<ConcertBrut> tous = new ArrayList<>();
    for (SourceConcerts source : sources) {
      try {
        tous.addAll(source.fetchRegion(lotDepts, null));
      } catch (Exception e) {
        log.warn("{} fetchRegion échoué depts={} : {}", source.nom(), lotDepts, e.getMessage());
      }
    }

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:region depts={} bruts={} dédoublonnés={}",
        lotDepts.size(),
        tous.size(),
        dedoublonnes.size());

    return persisterConcerts.persisterConcerts(dedoublonnes);
  }

  /// Fetch les concerts d'une ou plusieurs salles identifiées par leurs identifiants préfixés.
  ///
  /// <p>Le paramètre est une liste d'identifiants séparés par une virgule, chacun préfixé
  /// par la source : {@code "tm:rZFSnyZdFvA"} ou {@code "tm:id1,digitick:slug"}.
  ///
  /// @param identifiantsStr identifiants préfixés séparés par des virgules
  public void executerFetchSalle(String identifiantsStr) {
    log.info("fetch:salle démarré identifiants={}", identifiantsStr);
    if (!gestionQuotaTM.verifier("fetch:salle")) return;

    String[] identifiants = identifiantsStr.split(",");
    List<ConcertBrut> concerts = new ArrayList<>();
    for (String identifiant : identifiants) {
      fetchEtSuivreSalle(identifiant.trim(), concerts);
    }
    sauvegarderQuota();

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(concerts);
    log.info(
        "fetch:salle identifiants={} bruts={} dédoublonnés={}",
        identifiantsStr,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    recalculerMatchesService.recalculerMatches(null);
    log.info("fetch:salle terminé identifiants={}", identifiantsStr);
  }

  /// Fetch périodique de toutes les salles ayant des sources configurées dans {@code
  // salle_sources}.
  ///
  /// Boucle sur toutes les salles du référentiel et exécute le même pipeline que
  /// {@link #executerFetchSalle} en agrégeant les concerts avant persistance.
  /// Suspendu si le quota TM est insuffisant.
  public void executerFetchSallesAll() {
    log.info("fetch:salles:all démarré");
    long debut = System.currentTimeMillis();
    String erreur = null;
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    try {
      if (!gestionQuotaTM.cronAutorise()) {
        log.warn("fetch:salles:all suspendu — quota TM insuffisant");
        erreur = "quota insuffisant";
        return;
      }

      List<String> identifiantsParSalle =
          jdbc.queryForList(
              "SELECT string_agg(ss.source || ':' || ss.identifiant_externe, ',')"
                  + " FROM salle_sources ss GROUP BY ss.salle_id",
              Map.of(),
              String.class);

      if (identifiantsParSalle.isEmpty()) {
        log.info("fetch:salles:all — aucune salle avec source configurée");
        return;
      }

      for (int i = 0; i < identifiantsParSalle.size(); i += TAILLE_LOT_SALLES) {
        List<String> lotSalles =
            identifiantsParSalle.subList(
                i, Math.min(i + TAILLE_LOT_SALLES, identifiantsParSalle.size()));
        try {
          resultat = accumuler(resultat, traiterLotSalles(lotSalles));
        } catch (Exception e) {
          log.error(
              "fetch:salles:all lot {}-{} échoué : {}", i, i + lotSalles.size(), e.getMessage(), e);
        }
      }
      sauvegarderQuota();
      recalculerMatchesService.recalculerMatches(null);
      notificationDigestService.executerDigest("temps_reel");
      log.info("fetch:salles:all terminé");
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("fetch:salles:all échoué", e);
    } finally {
      enregistrerStatJob("fetch:salles:all", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Fetch + dédoublonnage + persistance d'un lot de salles (taille {@value TAILLE_LOT_SALLES}).
  ///
  /// <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que
  /// {@link #traiterLotArtistesBatch}) : chaque chaîne d'identifiants regroupe déjà toutes les
  /// sources d'une même salle ({@code GROUP BY ss.salle_id}), le dédoublonnage cross-sources reste
  /// donc correct à cette granularité.
  ///
  /// @param lotSalles sous-ensemble de chaînes d'identifiants préfixés, une par salle
  /// @return bilan de persistance du lot
  private ResultatPersistance traiterLotSalles(List<String> lotSalles) {
    List<ConcertBrut> tous = new ArrayList<>();
    for (String identifiantsStr : lotSalles) {
      if (identifiantsStr == null) continue;
      for (String id : identifiantsStr.split(",")) {
        fetchEtSuivreSalle(id.trim(), tous);
      }
    }

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
    log.info(
        "fetch:salles:all salles={} bruts={} dédoublonnés={}",
        lotSalles.size(),
        tous.size(),
        dedoublonnes.size());

    return persisterConcerts.persisterConcerts(dedoublonnes);
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// @param userIds IDs à recalculer, ou `null` pour tous
  public void executerRecalculerMatches(List<String> userIds) {
    log.info("recalculer:matches démarré userIds={}", userIds);
    long debut = System.currentTimeMillis();
    String erreur = null;
    try {
      int nbUsers = recalculerMatchesService.recalculerMatches(userIds);
      notificationDigestService.executerDigest("temps_reel");
      log.info("recalculer:matches terminé users={}", nbUsers);
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("recalculer:matches échoué", e);
    } finally {
      enregistrerStatJob(
          "recalculer:matches",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          erreur);
    }
  }

  /// Supprime les concerts passés depuis plus de 7 jours.
  public void executerPurgerConcerts() {
    LocalDate dateLimite = LocalDate.now().minusDays(7);
    log.info("purger:concerts dateLimite={}", dateLimite);
    long debut = System.currentTimeMillis();
    String erreur = null;
    try {
      int supprimes =
          jdbc.update(
              "DELETE FROM concerts WHERE date < :dateLimite", Map.of("dateLimite", dateLimite));
      log.info("purger:concerts terminé supprimes={}", supprimes);
      enregistrerStatJob(
          "purger:concerts",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("purger:concerts échoué", e);
      enregistrerStatJob(
          "purger:concerts",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          erreur);
    }
  }

  /// Supprime les salles orphelines (sans concerts futurs, sans utilisateurs et sans slug
  // Digitick).
  ///
  /// Une salle est conservée si :
  /// - elle possède au moins un concert futur (`date >= CURRENT_DATE`), ou
  /// - au moins un utilisateur la suit, ou
  /// - elle a un slug Digitick configuré (salle source Digitick dans `salle_sources`).
  ///
  /// Doit s'exécuter après {@link #executerPurgerConcerts()} pour travailler
  /// sur un jeu de données cohérent (cf. `PurgerSallesJob`).
  public void executerPurgerSalles() {
    log.info("purger:salles démarré");
    long debut = System.currentTimeMillis();
    try {
      int supprimes =
          jdbc.update(
              """
              DELETE FROM salles
              WHERE NOT EXISTS (
                  SELECT 1 FROM concerts WHERE salle_uuid = salles.id AND date >= CURRENT_DATE)
                AND NOT EXISTS (
                  SELECT 1 FROM user_salles WHERE salle_uuid = salles.id)
                AND NOT EXISTS (
                  SELECT 1 FROM salle_sources WHERE salle_id = salles.id AND source = 'digitick')
              """,
              Map.of());
      log.info("purger:salles terminé supprimes={}", supprimes);
      enregistrerStatJob(
          "purger:salles",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      log.error("purger:salles échoué", e);
      enregistrerStatJob(
          "purger:salles",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les artistes orphelins (sans concerts futurs ni utilisateurs).
  ///
  /// Un artiste est conservé si :
  /// - il possède au moins un concert futur (`date >= CURRENT_DATE`), ou
  /// - au moins un utilisateur le suit.
  ///
  /// Doit s'exécuter après {@link #executerPurgerSalles()} (cf. `PurgerArtistesJob`).
  public void executerPurgerArtistes() {
    log.info("purger:artistes démarré");
    long debut = System.currentTimeMillis();
    try {
      int supprimes =
          jdbc.update(
              """
              DELETE FROM artistes
              WHERE NOT EXISTS (
                  SELECT 1 FROM concerts WHERE artiste_id = artistes.id AND date >= CURRENT_DATE)
                AND NOT EXISTS (
                  SELECT 1 FROM user_artistes WHERE artiste_id = artistes.id)
              """,
              Map.of());
      log.info("purger:artistes terminé supprimes={}", supprimes);
      enregistrerStatJob(
          "purger:artistes",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      log.error("purger:artistes échoué", e);
      enregistrerStatJob(
          "purger:artistes",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Job nocturne de réconciliation des sources salles/artistes (#706) : health-check et
  /// redécouverte des identifiants suspects, puis enrichissement Wikidata/OSM/Deezer.
  public void executerReconciliationNocturne() {
    log.info("reconciliation:nocturne démarré");
    long debut = System.currentTimeMillis();
    String erreur = null;
    ResultatPersistance resultat = new ResultatPersistance(0, 0);
    try {
      var bilan = reconciliationNocturneService.executer();
      resultat =
          new ResultatPersistance(
              0,
              bilan.sallesRedecouvertes()
                  + bilan.enrichissementsSalle()
                  + bilan.enrichissementsArtiste());
      log.info(
          "reconciliation:nocturne terminé sallesVerifiees={} redecouvertes={} ambigues={}"
              + " enrichissementsSalle={} enrichissementsArtiste={}",
          bilan.sallesVerifiees(),
          bilan.sallesRedecouvertes(),
          bilan.ambigues(),
          bilan.enrichissementsSalle(),
          bilan.enrichissementsArtiste());
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("reconciliation:nocturne échoué", e);
    } finally {
      enregistrerStatJob(
          "reconciliation:nocturne", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Supprime les entrées de `logs_admin` plus anciennes que 30 jours,
  /// les entrées de `fetch_jobs_historique` plus anciennes que 7 jours,
  /// et les vérifications (tokens d'invitation, emails de confirmation) expirées
  /// depuis plus de 7 jours.
  ///
  /// Remplace l'ancien plafond de 200 entrées purgé à chaque insertion : la rétention
  /// est désormais temporelle et la purge planifiée (cf. `PurgerLogsAdminJob`).
  public void executerPurgerLogsAdmin() {
    long seuilLogs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    Instant seuilHistorique = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    Instant seuilVerifications = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    log.info("purger:logs-admin seuilLogs={} seuilHistorique={}", seuilLogs, seuilHistorique);
    long debut = System.currentTimeMillis();
    try {
      int supprimesLogs =
          jdbc.update("DELETE FROM logs_admin WHERE ts < :seuil", Map.of("seuil", seuilLogs));
      int supprimesHistorique =
          jdbc.update(
              "DELETE FROM fetch_jobs_historique WHERE exec_le < :seuil",
              Map.of("seuil", Timestamp.from(seuilHistorique)));
      int supprimesVerifications =
          jdbc.update(
              "DELETE FROM verifications WHERE expires_at < :seuil",
              Map.of("seuil", Timestamp.from(seuilVerifications)));
      log.info(
          "purger:logs-admin terminé supprimesLogs={} supprimesHistorique={}"
              + " supprimesVerifications={}",
          supprimesLogs,
          supprimesHistorique,
          supprimesVerifications);
      enregistrerStatJob(
          "purger:logs-admin",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimesLogs + supprimesHistorique + supprimesVerifications),
          null);
    } catch (Exception e) {
      log.error("purger:logs-admin échoué", e);
      enregistrerStatJob(
          "purger:logs-admin",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Révoque les admins dont le délai de préavis est écoulé.
  ///
  /// <p>Passe de `admin` à `user` tous les comptes dont `revocation_admin_le` est dans le passé,
  /// puis révoque leurs sessions actives (#809) — sans cela, un admin rétrogradé resterait
  /// authentifié avec son ancien rôle `admin` (chargé en session à la connexion, jamais
  /// rafraîchi) jusqu'à expiration naturelle de sa session (30 jours). Le module worker n'a pas
  /// le bean {@code FindByIndexNameSessionRepository} (spécifique à Spring Session côté web) :
  /// suppression directe des lignes {@code SPRING_SESSION} par {@code PRINCIPAL_NAME}, table
  /// déjà lue en lecture seule ailleurs (voir {@code AdminService.listerUtilisateurs}).
  /// Loggue le bilan dans `logs_admin`.
  public void executerRevoquerAdmins() {
    log.info("revoquer:admins démarré");
    long debut = System.currentTimeMillis();
    try {
      List<String> usernamesRevoques =
          jdbc.queryForList(
              "SELECT username FROM users WHERE revocation_admin_le < now() AND role = 'admin'",
              Map.of(),
              String.class);
      int nb =
          jdbc.update(
              """
              UPDATE users SET role = 'user', revocation_admin_le = NULL
              WHERE revocation_admin_le < now() AND role = 'admin'
              """,
              Map.of());
      log.info("revoquer:admins terminé nb={}", nb);
      if (nb > 0) {
        revoquerSessions(usernamesRevoques);
        jdbc.update(
            """
            INSERT INTO logs_admin (ts, niveau, source, message, meta)
            VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
            """,
            Map.of(
                "ts", System.currentTimeMillis(),
                "niveau", "warn",
                "source", "admin",
                "message", "révocation admin effective — " + nb + " compte(s) rétrogradé(s)",
                "meta", "{}"));
      }
      enregistrerStatJob(
          "revoquer:admins",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, nb),
          null);
    } catch (Exception e) {
      log.error("revoquer:admins échoué", e);
      enregistrerStatJob(
          "revoquer:admins",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les sessions Spring Session actives pour les identifiants fournis (#809).
  ///
  /// @param usernames identifiants (colonne {@code username}) des comptes venant d'être rétrogradés
  private void revoquerSessions(List<String> usernames) {
    if (usernames.isEmpty()) return;
    try {
      int supprimees =
          jdbc.update(
              "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME IN (:usernames)",
              Map.of("usernames", usernames));
      if (supprimees > 0) {
        log.info("revoquer:admins — sessions révoquées count={}", supprimees);
      }
    } catch (Exception e) {
      log.warn("revoquer:admins — impossible de révoquer les sessions : {}", e.getMessage());
    }
  }

  private static java.util.Set<String> collecterTousLesDepts(List<String[]> allDepts) {
    java.util.Set<String> tousLesDepts = new java.util.LinkedHashSet<>();
    for (String[] depts : allDepts) {
      if (depts != null) {
        for (String d : depts) {
          if (d != null && !d.isBlank()) tousLesDepts.add(d.trim());
        }
      }
    }
    return tousLesDepts;
  }

  private List<ConcertBrut> resoudreEtDeduplicer(List<ConcertBrut> concerts) {
    List<ConcertBrut> dedoublonnes = Dedoublonner.dedoublonner(concerts);

    Map<String, String> tableauResolution = new LinkedHashMap<>();
    List<String> noms = dedoublonnes.stream().map(ConcertBrut::artiste).distinct().toList();

    for (String nom : noms) {
      ResolveurNomCanonique.ResultatResolution resultat =
          resolveur.resoudreNomCanonique("artiste", nom);
      tableauResolution.put(nom, resultat.nom());
    }

    return dedoublonnes.stream()
        .map(
            c -> {
              String nomResolu = tableauResolution.getOrDefault(c.artiste(), c.artiste());
              if (nomResolu.equals(c.artiste())) return c;
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

  private void enregistrerStatJob(
      String typeJob, long dureeMs, ResultatPersistance resultat, String erreur) {
    Timestamp maintenant = Timestamp.from(Instant.now());
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", typeJob);
      params.put("now", maintenant);
      params.put("dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE));
      params.put("nbInseres", resultat.nbInseres());
      params.put("nbMisAJour", resultat.nbMisAJour());
      params.put("erreur", erreur);
      jdbc.update(
          """
          INSERT INTO fetch_jobs (type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, :nbInseres, :nbMisAJour, :erreur)
          ON CONFLICT (type_job) DO UPDATE SET
            derniere_exec  = EXCLUDED.derniere_exec,
            duree_ms       = EXCLUDED.duree_ms,
            nb_inseres     = EXCLUDED.nb_inseres,
            nb_mis_a_jour  = EXCLUDED.nb_mis_a_jour,
            erreur         = EXCLUDED.erreur
          """,
          params);
      jdbc.update(
          """
          INSERT INTO fetch_jobs_historique
            (type_job, exec_le, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, :nbInseres, :nbMisAJour, :erreur)
          """,
          params);
    } catch (Exception e) {
      log.warn("Impossible d'enregistrer stat job {} : {}", typeJob, e.getMessage());
    }
    if (typeJob.startsWith("fetch:")) {
      boolean bilanLoggue = false;
      for (SourceConcerts source : sources) {
        if (source instanceof SourceAvecBilanFetch avecBilan) {
          bilanLoggue |= remonterBilan(typeJob, source.nom(), avecBilan.preleverBilan());
        }
      }
      bilanLoggue |= remonterBilan(typeJob, "deezer", resolveur.preleverBilan());
      bilanLoggue |= remonterBilanConcertsManquesTm(typeJob);
      if (!bilanLoggue
          && erreur == null
          && resultat.nbInseres() == 0
          && resultat.nbMisAJour() == 0) {
        remonterResultatVide(typeJob, maintenant);
      }
    }
  }

  /// Remonte dans `logs_admin` le bilan agrégé d'appels/erreurs d'une source pour le cycle qui
  /// vient de se terminer (#909/#912) — mécanisme générique remplaçant les anciennes méthodes
  /// dédiées `remonterBilanTm`/`remonterBilanEventim` : une panne totale (100% d'échecs sur un
  /// nombre suffisant d'appels — voir {@link BilanFetch#SEUIL_APPELS_MIN_ALERTE_DEFAUT}) est
  /// remontée en niveau {@code error}, captée automatiquement par {@code AlerteLogsAdminService}
  /// (digest niveau erreur) ; un échec partiel reste en {@code warn}.
  ///
  /// @param typeJob   le type de job de fetch (préfixe `fetch:`)
  /// @param nomSource nom de la source (ex. {@code "eventim"}, capitalisé pour l'affichage)
  /// @param bilan     bilan prélevé pour cette source depuis le dernier cycle
  /// @return {@code true} si une entrée a été insérée dans {@code logs_admin}
  private boolean remonterBilan(String typeJob, String nomSource, BilanFetch bilan) {
    if (bilan == null) return false;
    return bilan
        .evaluer(typeJob, capitaliser(nomSource), BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT)
        .map(
            entree -> {
              logsAdminWorker.inserer(entree.niveau(), "worker", entree.message(), entree.meta());
              return true;
            })
        .orElse(false);
  }

  private static String capitaliser(String nom) {
    return nom.isEmpty() ? nom : Character.toUpperCase(nom.charAt(0)) + nom.substring(1);
  }

  /// Remonte dans `logs_admin` le nombre de concerts TM non récupérés par plafonnement de la
  /// pagination pour le cycle qui vient de se terminer. Métrique spécifique à TM, distincte du
  /// bilan appels/erreurs commun porté par {@link #remonterBilan} (#909).
  ///
  /// @param typeJob le type de job de fetch (préfixe `fetch:`)
  /// @return {@code true} si un warn a été inséré dans {@code logs_admin}
  private boolean remonterBilanConcertsManquesTm(String typeJob) {
    int concertsManques = tm.preleverConcertsManques();
    if (concertsManques == 0) return false;
    logsAdminWorker.inserer(
        "warn",
        "worker",
        typeJob + " — " + concertsManques + " concert(s) TM non récupéré(s) (pagination plafonnée)",
        Map.of("manques", concertsManques));
    return true;
  }

  /// Remonte dans `logs_admin` un warn de résultat vide (nb_inseres + nb_mis_a_jour = 0)
  /// pour un fetch qui s'est terminé sans erreur mais sans aucun concert persisté.
  ///
  /// @param typeJob  le type de job de fetch
  /// @param execLe   l'horodatage de l'exécution
  private void remonterResultatVide(String typeJob, Timestamp execLe) {
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("ts", execLe.getTime());
      params.put("niveau", "warn");
      params.put("source", "worker");
      params.put("message", typeJob + " — 0 concert inséré ou mis à jour (résultat vide)");
      params.put("meta", "{}");
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          params);
    } catch (Exception e) {
      log.warn("Impossible de remonter le résultat vide pour {} : {}", typeJob, e.getMessage());
    }
  }

  private void sauvegarderQuota() {
    var quota = tm.obtenirQuota();
    if (quota != null) gestionQuotaTM.ecrireQuota(quota);
  }

  private void attendreRateLimit() {
    try {
      Thread.sleep(DELAI_ENTRE_ARTISTES_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
