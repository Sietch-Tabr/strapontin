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

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final ResolveurNomCanonique resolveur;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NotificationDigestService notificationDigestService;
  private final NamedParameterJdbcTemplate jdbc;

  public WorkerService(
      List<SourceConcerts> sources,
      TicketmasterSource tm,
      ResolveurNomCanonique resolveur,
      PersisterConcerts persisterConcerts,
      RecalculerMatchesService recalculerMatchesService,
      GestionQuotaTM gestionQuotaTM,
      NotificationDigestService notificationDigestService,
      NamedParameterJdbcTemplate jdbc) {
    this.sources = sources;
    this.tm = tm;
    this.resolveur = resolveur;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.notificationDigestService = notificationDigestService;
    this.jdbc = jdbc;
  }

  /// Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
  ///
  /// Suspendu si le quota TM est inférieur à 500 (SEUIL_QUOTA_CRON).
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
              "SELECT DISTINCT artiste_affiche, artiste_normalise, attraction_id FROM"
                  + " user_artistes",
              Map.of());

      if (rows.isEmpty()) {
        log.info("fetch:artistes:batch — aucun artiste suivi");
        return;
      }

      List<ConcertBrut> concertsTM = fetchTMParArtistes(rows);
      sauvegarderQuota();

      List<SourceConcerts> sourcesSimples =
          sources.stream().filter(s -> !(s instanceof SourceAvecFetchLot)).toList();
      List<ConcertBrut> concertsAutresSources = new ArrayList<>();
      for (SourceConcerts source : sourcesSimples) {
        for (Map<String, Object> row : rows) {
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
      log.info("fetch:artistes:batch bruts={} dédoublonnés={}", tous.size(), dedoublonnes.size());

      resultat = persisterConcerts.persisterConcerts(dedoublonnes);
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
  /// @param rows lignes de {@code user_artistes} avec {@code artiste_affiche}, {@code
  ///     artiste_normalise}, {@code attraction_id}
  /// @return concerts bruts collectés depuis TM
  private List<ConcertBrut> fetchTMParArtistes(List<Map<String, Object>> rows) {
    List<ConcertBrut> resultats = new ArrayList<>();
    List<String> attractionIds = new ArrayList<>();
    List<String> nomsSansTmId = new ArrayList<>();

    for (Map<String, Object> row : rows) {
      String nom = (String) row.get("artiste_affiche");
      String norm = (String) row.get("artiste_normalise");
      String attractionId = (String) row.get("attraction_id");
      if (attractionId == null) {
        try {
          attractionId = tm.rechercherAttractionId(nom);
          if (attractionId != null) {
            jdbc.update(
                "UPDATE user_artistes SET attraction_id = :id WHERE artiste_normalise = :norm",
                Map.of("id", attractionId, "norm", norm));
          }
        } catch (Exception e) {
          log.warn("rechercherAttractionId échoué artiste={} : {}", nom, e.getMessage());
        }
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
        resultats.addAll(tm.fetchArtistesLot(lot));
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
      List<ConcertBrut> tous = new ArrayList<>();
      for (SourceConcerts source : sources) {
        try {
          tous.addAll(source.fetchRegion(deptsListe, TicketmasterSource.CLASSIFICATIONS));
        } catch (Exception e) {
          log.warn("{} fetchRegion échoué : {}", source.nom(), e.getMessage());
        }
      }
      sauvegarderQuota();

      List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
      log.info(
          "fetch:region depts={} bruts={} dédoublonnés={}",
          tousLesDepts.size(),
          tous.size(),
          dedoublonnes.size());

      resultat = persisterConcerts.persisterConcerts(dedoublonnes);
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
      String id = identifiant.trim();
      for (SourceConcerts source : sources) {
        if (source.accepteIdentifiantSalle(id)) {
          try {
            concerts.addAll(source.fetchSalle(id));
          } catch (Exception e) {
            log.error("{} fetchSalle échoué identifiant={} : {}", source.nom(), id, e.getMessage());
          }
        }
      }
    }
    sauvegarderQuota();

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(concerts);
    log.info(
        "fetch:salle identifiants={} bruts={} dédoublonnés={}",
        identifiantsStr,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    log.info("fetch:salle terminé identifiants={}", identifiantsStr);
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

  /// Supprime les entrées de `logs_admin` plus anciennes que 30 jours
  /// et les entrées de `fetch_jobs_historique` plus anciennes que 7 jours.
  ///
  /// Remplace l'ancien plafond de 200 entrées purgé à chaque insertion : la rétention
  /// est désormais temporelle et la purge planifiée (cf. `PurgerLogsAdminJob`).
  public void executerPurgerLogsAdmin() {
    long seuilLogs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    Instant seuilHistorique = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    log.info("purger:logs-admin seuilLogs={} seuilHistorique={}", seuilLogs, seuilHistorique);
    long debut = System.currentTimeMillis();
    try {
      int supprimesLogs =
          jdbc.update("DELETE FROM logs_admin WHERE ts < :seuil", Map.of("seuil", seuilLogs));
      int supprimesHistorique =
          jdbc.update(
              "DELETE FROM fetch_jobs_historique WHERE exec_le < :seuil",
              Map.of("seuil", Timestamp.from(seuilHistorique)));
      log.info(
          "purger:logs-admin terminé supprimesLogs={} supprimesHistorique={}",
          supprimesLogs,
          supprimesHistorique);
      enregistrerStatJob(
          "purger:logs-admin",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimesLogs + supprimesHistorique),
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
  /// <p>Passe de `admin` à `user` tous les comptes dont `revocation_admin_le` est dans le passé.
  /// Loggue le bilan dans `logs_admin`.
  public void executerRevoquerAdmins() {
    log.info("revoquer:admins démarré");
    long debut = System.currentTimeMillis();
    try {
      int nb =
          jdbc.update(
              """
              UPDATE users SET role = 'user', revocation_admin_le = NULL
              WHERE revocation_admin_le < now() AND role = 'admin'
              """,
              Map.of());
      log.info("revoquer:admins terminé nb={}", nb);
      if (nb > 0) {
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
                  c.prixMin(),
                  c.prixMax(),
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
      boolean bilanLoggue = remonterBilanTm(typeJob);
      if (!bilanLoggue
          && erreur == null
          && resultat.nbInseres() == 0
          && resultat.nbMisAJour() == 0) {
        remonterResultatVide(typeJob, maintenant);
      }
    }
  }

  /// Remonte dans `logs_admin` le bilan agrégé des fetchs Ticketmaster (concerts manqués
  /// par plafonnement de pagination, erreurs HTTP) pour le cycle qui vient de se terminer.
  ///
  /// Une seule entrée par cycle de fetch, et uniquement si quelque chose est à signaler.
  ///
  /// @param typeJob le type de job de fetch (préfixe `fetch:`)
  /// @return {@code true} si un warn a été inséré dans {@code logs_admin}
  private boolean remonterBilanTm(String typeJob) {
    var bilan = tm.preleverBilan();
    if (bilan == null || (bilan.concertsManques() == 0 && bilan.erreursHttp() == 0)) return false;
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("ts", System.currentTimeMillis());
      params.put("niveau", "warn");
      params.put("source", "worker");
      params.put(
          "message",
          typeJob
              + " — "
              + bilan.concertsManques()
              + " concert(s) TM non récupéré(s), "
              + bilan.erreursHttp()
              + " erreur(s) HTTP");
      params.put(
          "meta",
          "{\"manques\":"
              + bilan.concertsManques()
              + ",\"erreursHttp\":"
              + bilan.erreursHttp()
              + "}");
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          params);
      return true;
    } catch (Exception e) {
      log.warn("Impossible de remonter le bilan TM : {}", e.getMessage());
      return false;
    }
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
