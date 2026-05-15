/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
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
/// <p>Délégué par les {@link fr.strapontin.worker.jobs} Quartz. Chaque méthode correspond
/// à un type de job BullMQ porté en Java.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class WorkerService {

  private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

  private static final List<String> CLASSIFICATIONS = List.of("music", "opera", "musical");
  private static final int DELAI_ENTRE_ARTISTES_MS = 250;

  private final TicketmasterSource tm;
  private final EventimSource eventim;
  private final ResolveurNomCanonique resolveur;
  private final PersisterConcerts persisterConcerts;
  private final RecalculerMatchesService recalculerMatchesService;
  private final GestionQuotaTM gestionQuotaTM;
  private final NamedParameterJdbcTemplate jdbc;

  public WorkerService(
      TicketmasterSource tm,
      EventimSource eventim,
      ResolveurNomCanonique resolveur,
      PersisterConcerts persisterConcerts,
      RecalculerMatchesService recalculerMatchesService,
      GestionQuotaTM gestionQuotaTM,
      NamedParameterJdbcTemplate jdbc) {
    this.tm = tm;
    this.eventim = eventim;
    this.resolveur = resolveur;
    this.persisterConcerts = persisterConcerts;
    this.recalculerMatchesService = recalculerMatchesService;
    this.gestionQuotaTM = gestionQuotaTM;
    this.jdbc = jdbc;
  }

  /// Fetch global de tous les artistes suivis (TM par attraction ID + Eventim individuel).
  ///
  /// <p>Suspendu si le quota TM est inférieur à {@link GestionQuotaTM#SEUIL_QUOTA_CRON}.
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
              "SELECT DISTINCT artiste_affiche, artiste_normalise FROM user_artistes", Map.of());

      if (rows.isEmpty()) {
        log.info("fetch:artistes:batch — aucun artiste suivi");
        return;
      }

      List<ConcertBrut> concertsTM = fetchTMParArtistes(rows);
      sauvegarderQuota();

      List<ConcertBrut> concertsEventim = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        String nom = (String) row.get("artiste_affiche");
        try {
          concertsEventim.addAll(eventim.fetchArtiste(nom));
        } catch (Exception e) {
          log.warn("Eventim échoué pour artiste={} : {}", nom, e.getMessage());
        }
        attendreRateLimit();
      }

      List<ConcertBrut> tous = new ArrayList<>(concertsTM);
      tous.addAll(concertsEventim);

      List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
      log.info("fetch:artistes:batch bruts={} dédoublonnés={}", tous.size(), dedoublonnes.size());

      resultat = persisterConcerts.persisterConcerts(dedoublonnes);
      recalculerMatchesService.recalculerMatches(null);
      log.info("fetch:artistes:batch terminé");
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("fetch:artistes:batch échoué", e);
    } finally {
      enregistrerStatJob("fetch:artistes:batch", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  private List<ConcertBrut> fetchTMParArtistes(List<Map<String, Object>> rows) {
    List<ConcertBrut> resultats = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String nom = (String) row.get("artiste_affiche");
      String norm = (String) row.get("artiste_normalise");
      try {
        String attractionId = tm.rechercherAttractionId(nom);
        if (attractionId != null) {
          resultats.addAll(tm.fetchArtistesBatch(List.of(attractionId)));
        } else {
          resultats.addAll(tm.fetchArtiste(nom));
        }
      } catch (Exception e) {
        log.warn("TM échoué pour artiste={} norm={} : {}", nom, norm, e.getMessage());
      }
      attendreRateLimit();
    }
    return resultats;
  }

  /// Fetch par région pour tous les départements configurés.
  ///
  /// <p>Suspendu si le quota TM est insuffisant.
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

      java.util.Set<String> tousLesDepts = new java.util.LinkedHashSet<>();
      for (String[] depts : allDepts) {
        if (depts != null) {
          for (String d : depts) {
            if (d != null && !d.isBlank()) tousLesDepts.add(d.trim());
          }
        }
      }

      if (tousLesDepts.isEmpty()) {
        log.info("fetch:region — aucun département configuré");
        return;
      }

      List<String> deptsListe = new ArrayList<>(tousLesDepts);
      List<ConcertBrut> tous = new ArrayList<>();
      try {
        tous.addAll(tm.fetchRegion(deptsListe, CLASSIFICATIONS));
      } catch (Exception e) {
        log.warn("TM fetchRegion échoué : {}", e.getMessage());
      }
      sauvegarderQuota();

      try {
        tous.addAll(eventim.fetchRegion(deptsListe, CLASSIFICATIONS));
      } catch (Exception e) {
        log.warn("Eventim fetchRegion échoué : {}", e.getMessage());
      }

      List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(tous);
      log.info(
          "fetch:region depts={} bruts={} dédoublonnés={}",
          tousLesDepts.size(),
          tous.size(),
          dedoublonnes.size());

      resultat = persisterConcerts.persisterConcerts(dedoublonnes);
      log.info("fetch:region terminé");
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("fetch:region échoué", e);
    } finally {
      enregistrerStatJob("fetch:region", System.currentTimeMillis() - debut, resultat, erreur);
    }
  }

  /// Fetch les concerts d'une salle identifiée par son {@code venueId} Ticketmaster.
  ///
  /// @param venueId identifiant TM de la salle
  public void executerFetchSalle(String venueId) {
    log.info("fetch:salle démarré venueId={}", venueId);
    if (!gestionQuotaTM.verifier("fetch:salle")) return;

    List<ConcertBrut> concerts;
    try {
      concerts = tm.fetchSalle(venueId);
    } catch (Exception e) {
      log.error("TM fetchSalle échoué venueId={} : {}", venueId, e.getMessage());
      return;
    }
    sauvegarderQuota();

    List<ConcertBrut> dedoublonnes = resoudreEtDeduplicer(concerts);
    log.info(
        "fetch:salle venueId={} bruts={} dédoublonnés={}",
        venueId,
        concerts.size(),
        dedoublonnes.size());
    persisterConcerts.persisterConcerts(dedoublonnes);
    log.info("fetch:salle terminé venueId={}", venueId);
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// @param userIds IDs à recalculer, ou {@code null} pour tous
  public void executerRecalculerMatches(List<String> userIds) {
    log.info("recalculer:matches démarré userIds={}", userIds);
    recalculerMatchesService.recalculerMatches(userIds);
    log.info("recalculer:matches terminé");
  }

  /// Supprime les concerts passés depuis plus de 7 jours.
  public void executerPurgerConcerts() {
    LocalDate dateLimite = LocalDate.now().minusDays(7);
    log.info("purger:concerts dateLimite={}", dateLimite);
    int supprimes =
        jdbc.update(
            "DELETE FROM concerts WHERE date < :dateLimite", Map.of("dateLimite", dateLimite));
    log.info("purger:concerts terminé supprimes={}", supprimes);
  }

  /// Stub pour les notifications quotidiennes — implémenté en Phase 4.
  public void executerNotifierDigest() {
    log.info("notifier:digest — implémentation prévue en Phase 4");
  }

  private List<ConcertBrut> resoudreEtDeduplicer(List<ConcertBrut> concerts) {
    List<ConcertBrut> dedoublonnes = Dedoublonner.dedoublonner(concerts);

    Map<String, String> tableauResolution = new LinkedHashMap<>();
    List<String> noms = dedoublonnes.stream().map(ConcertBrut::artiste).distinct().toList();

    for (String nom : noms) {
      String norm = Normalisation.normaliserTexte(nom);
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
                  c.statut());
            })
        .toList();
  }

  private void enregistrerStatJob(
      String typeJob, long dureeMs, ResultatPersistance resultat, String erreur) {
    try {
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
          Map.of(
              "typeJob", typeJob,
              "now", Timestamp.from(Instant.now()),
              "dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE),
              "nbInseres", resultat.nbInseres(),
              "nbMisAJour", resultat.nbMisAJour(),
              "erreur", erreur != null ? erreur : ""));
    } catch (Exception e) {
      log.warn("Impossible d'enregistrer stat job {} : {}", typeJob, e.getMessage());
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
