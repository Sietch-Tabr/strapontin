/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceAvecFetchLot;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Orchestration des jobs de fetch : fetch → dédoublonnage → résolution → persistance → recalcul.
/// Extrait de {@code WorkerService}.
///
/// Délégué par les jobs Quartz {@code FetchArtistesBatchJob}, {@code FetchRegionJob}, {@code
/// FetchSalleJob}, {@code FetchSallesAllJob}.
///
/// @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
@Service
public class WorkerFetchService {

  private static final Logger log = LoggerFactory.getLogger(WorkerFetchService.class);

  private static final int DELAI_ENTRE_ARTISTES_MS = 250;
  // Deezer ne référence pas les humoristes — toute recherche pour un humoriste renvoie le
  // musicien le plus proche textuellement (ex. "Fabrice Éboué" → "Fabrice Aboulker"), jamais
  // détecté par le seuil de similarité seul. La classification "theatre" (TM : segment "arts et
  // théâtre") est le signal le plus fiable pour distinguer un spectacle d'humour/théâtral d'un
  // concert — Digitick filtre déjà en amont sur le genre "Concert" (jamais de classification
  // "theatre", classification systématiquement absente par construction, sans ambiguïté). Eventim
  // en revanche omet fréquemment sa classification (ex. "Paris is Magique", comédie musicale
  // sans rapport avec la musique réelle) : une classification vide côté Eventim ne garantit rien
  // sur la nature musicale du concert, contrairement à Digitick.
  private static final String CLASSIFICATION_THEATRE = "theatre";
  private static final String SOURCE_EVENTIM = "eventim";
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

  public WorkerFetchService(
      List<SourceConcerts> sources,
      TicketmasterSource tm,
      ResolveurNomCanonique resolveur,
      PersisterConcerts persisterConcerts,
      RecalculerMatchesService recalculerMatchesService,
      GestionQuotaTM gestionQuotaTM,
      NotificationDigestService notificationDigestService,
      NamedParameterJdbcTemplate jdbc,
      SuiviSanteSourcesService suiviSanteSourcesService,
      WorkerStatsService workerStatsService) {
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
  }

  /// Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
  ///
  /// Suspendu si le quota TM est inférieur à 500 (SEUIL_QUOTA_CRON). Une exception dans un lot
  /// n'interrompt pas les lots suivants : le recalcul de matchs et le digest de notification
  /// s'exécutent toujours sur la base des lots effectivement réussis, plutôt que d'être sautés en
  /// cas d'échec partiel.
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
                  + " MAX(asm.identifiant_externe) AS attraction_id,"
                  + " MAX(a.deezer_id) AS deezer_id,"
                  + " MAX(a.wikidata_id) AS wikidata_id"
                  + " FROM user_artistes ua"
                  + " JOIN artistes a ON a.id = ua.artiste_id"
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
      workerStatsService.enregistrerStatJob(
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
  /// @param lot sous-ensemble de lignes artiste (taille {@value TAILLE_LOT_TM})
  /// @param sourcesSimples sources hors {@link SourceAvecFetchLot} (fetch individuel par artiste)
  /// @return bilan de persistance du lot
  private ResultatPersistance traiterLotArtistesBatch(
      List<Map<String, Object>> lot, List<SourceConcerts> sourcesSimples) {
    List<ConcertBrut> concertsTM = fetchTMParArtistes(lot);

    List<ConcertBrut> concertsAutresSources = new ArrayList<>();
    for (SourceConcerts source : sourcesSimples) {
      for (Map<String, Object> row : lot) {
        String nom = (String) row.get("artiste_affiche");
        String deezerId = (String) row.get("deezer_id");
        String wikidataId = (String) row.get("wikidata_id");
        try {
          concertsAutresSources.addAll(source.fetchArtiste(nom, deezerId, wikidataId));
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

  /// Fetch une salle auprès de la source qui accepte son identifiant et enregistre le résultat
  /// auprès de {@link SuiviSanteSourcesService} (phase 1).
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

  /// Fetch TM pour une liste d'artistes suivis.
  ///
  /// <p>Deux passes :
  ///
  /// <ol>
  ///   <li>Résolution des {@code attractionId} manquants (1 requête /attractions.json par artiste
  ///       non résolu), puis fetch groupé par lots de {@value TAILLE_LOT_TM} via {@code
  ///       fetchArtistesBatch} — réduit la consommation de quota d'un facteur ×20 par rapport à un
  ///       fetch artiste-par-artiste.
  ///   <li>Fallback keyword ({@code fetchArtiste}) pour les artistes sans {@code attractionId} TM.
  /// </ol>
  ///
  /// @param rows lignes agrégées par {@code artiste_id} avec {@code artiste_affiche}, {@code
  ///   artiste_id_str}, {@code attraction_id}
  /// @return concerts bruts collectés depuis TM
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
        // throttle manquant ici jusqu'alors — un lot avec plusieurs artistes jamais
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
      workerStatsService.enregistrerStatJob(
          "fetch:region", System.currentTimeMillis() - debut, resultat, erreur);
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

  /// Fetch + dédoublonnage + persistance d'un lot de départements (taille {@value
  /// TAILLE_LOT_DEPTS}).
  ///
  /// <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que {@link
  /// #traiterLotArtistesBatch}) : le dédoublonnage cross-sources reste correct à cette granularité,
  /// un même concert n'étant jamais rapporté par deux sources dans deux départements différents.
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
  /// <p>Le paramètre est une liste d'identifiants séparés par une virgule, chacun préfixé par la
  /// source : {@code "tm:rZFSnyZdFvA"} ou {@code "tm:id1,digitick:slug"}.
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

  /// Fetch périodique des salles suivies (`user_salles`) ayant des sources configurées dans {@code
  /// salle_sources}.
  ///
  /// Restreint aux salles réellement suivies par un utilisateur depuis — jusque-là toutes les
  /// salles du référentiel (255 avec source TM en prod le 18/07/2026) étaient refetchées à chaque
  /// cycle, épuisant le quota TM en une journée dès que a débloqué cet appel pour de vrai. Les
  /// salles non suivies n'apportent aucune valeur ici : un artiste suivi qui y jouerait est de
  /// toute façon déjà détecté par {@code fetch:artistes:batch} (recherche nationale par nom,
  /// indépendante du département).
  ///
  /// Boucle sur les salles suivies et exécute le même pipeline que {@link #executerFetchSalle} en
  /// agrégeant les concerts avant persistance. Suspendu si le quota TM est insuffisant.
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

      // le préfixe emis ici doit correspondre à celui attendu par
      // TicketmasterSource.accepteIdentifiantSalle ("tm", pas la valeur canonique "ticketmaster"
      // de salle_sources.source) — sans cette traduction, fetchSalle n'est jamais invoqué pour
      // aucune salle TM (mismatch silencieux, aucune exception, aucun log d'erreur).
      // restreint aux salles suivies (user_salles) — voir Javadoc de la méthode.
      List<String> identifiantsParSalle =
          jdbc
              .queryForList(
                  "SELECT string_agg(ss.source || ':' || ss.identifiant_externe, ',')"
                      + " FROM salle_sources ss"
                      + " WHERE EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id ="
                      + " ss.salle_id)"
                      + " GROUP BY ss.salle_id",
                  Map.of(),
                  String.class)
              .stream()
              .map(
                  csv ->
                      csv == null
                          ? null
                          : csv.replace(
                              "ticketmaster:", TicketmasterSource.PREFIXE_IDENTIFIANT + ":"))
              .toList();

      if (identifiantsParSalle.isEmpty()) {
        log.info("fetch:salles:all — aucune salle suivie avec source configurée");
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
      workerStatsService.enregistrerStatJob(
          "fetch:salles:all", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Fetch + dédoublonnage + persistance d'un lot de salles (taille {@value TAILLE_LOT_SALLES}).
  ///
  /// <p>Persistance par lot plutôt qu'en un seul bloc final (même motif que {@link
  /// #traiterLotArtistesBatch}) : chaque chaîne d'identifiants regroupe déjà toutes les sources
  /// d'une même salle ({@code GROUP BY ss.salle_id}), le dédoublonnage cross-sources reste donc
  /// correct à cette granularité.
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

  /// Détermine si un concert est éligible à la résolution du nom canonique via Deezer.
  ///
  /// <p>Exclut la classification "theatre" (TM/musical/théâtral, jamais un vrai musicien côté
  /// Deezer) ainsi que, spécifiquement pour Eventim, une classification vide/absente — Digitick
  /// omet aussi sa classification mais uniquement pour de vrais concerts (filtré en amont sur le
  /// genre "Concert"), alors qu'Eventim l'omet fréquemment y compris pour des spectacles non
  /// musicaux.
  ///
  /// @param c concert à évaluer
  /// @return {@code true} si la résolution Deezer peut s'appliquer à ce concert
  private static boolean estEligibleResolutionDeezer(ConcertBrut c) {
    if (CLASSIFICATION_THEATRE.equals(c.classification())) return false;
    return !SOURCE_EVENTIM.equals(c.source())
        || (c.classification() != null && !c.classification().isBlank());
  }

  private List<ConcertBrut> resoudreEtDeduplicer(List<ConcertBrut> concerts) {
    List<ConcertBrut> dedoublonnes = Dedoublonner.dedoublonner(concerts);

    Map<String, String> tableauResolution = new LinkedHashMap<>();
    List<String> noms =
        dedoublonnes.stream()
            .filter(WorkerFetchService::estEligibleResolutionDeezer)
            .map(ConcertBrut::artiste)
            .distinct()
            .toList();

    for (String nom : noms) {
      ResolveurNomCanonique.ResultatResolution resultat =
          resolveur.resoudreNomCanonique("artiste", nom);
      tableauResolution.put(nom, resultat.nom());
    }

    return dedoublonnes.stream()
        .map(
            c -> {
              if (!estEligibleResolutionDeezer(c)) return c;
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
