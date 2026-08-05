/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Statistiques et bilans de panne des jobs Quartz — extrait de {@code WorkerService},
/// mécanisme partagé par tous les jobs (fetch, purge, révocation, réconciliation), pas seulement
/// le fetch.
///
/// @since 2.7.0
/// @author Kwaite
@Service
public class WorkerStatsService {

  private static final Logger log = LoggerFactory.getLogger(WorkerStatsService.class);

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final ResolveurNomCanonique resolveur;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final MeterRegistry meterRegistry;

  /// @param sources    sources de concerts (bilan de panne par source)
  /// @param tm         source Ticketmaster (bilan des concerts manqués par pagination plafonnée)
  /// @param resolveur  résolveur Deezer (bilan de panne dédié)
  /// @param jdbc       accès JDBC pour {@code fetch_jobs}/{@code fetch_jobs_historique}
  /// @param logsAdminWorker journalisation des bilans dans {@code logs_admin}
  /// @param meterRegistry registre Micrometer pour la métrique de durée par type de job
  public WorkerStatsService(
      List<SourceConcerts> sources,
      TicketmasterSource tm,
      ResolveurNomCanonique resolveur,
      NamedParameterJdbcTemplate jdbc,
      LogsAdminWorker logsAdminWorker,
      MeterRegistry meterRegistry) {
    this.sources = sources;
    this.tm = tm;
    this.resolveur = resolveur;
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.meterRegistry = meterRegistry;
  }

  /// Enregistre le résultat d'un job dans {@code fetch_jobs}/{@code fetch_jobs_historique}, puis
  /// remonte les bilans de panne agrégés pour les jobs de type {@code fetch:}.
  ///
  /// @param typeJob type de job (ex. {@code "fetch:region"}, {@code "purger:concerts"})
  /// @param dureeMs durée d'exécution en millisecondes
  /// @param resultat bilan de persistance (insertions/mises à jour)
  /// @param erreur   message d'erreur, ou {@code null} si le job s'est terminé sans exception
  public void enregistrerStatJob(
      String typeJob, long dureeMs, ResultatPersistance resultat, String erreur) {
    Timestamp maintenant = Timestamp.from(Instant.now());
    Timer.builder("strapontin.worker.job.duration")
        .description("Durée d'exécution d'un job worker (fetch/purge/révocation/réconciliation)")
        .tag("type_job", typeJob)
        .register(meterRegistry)
        .record(dureeMs, TimeUnit.MILLISECONDS);
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
        if (source instanceof EventimSource eventimSource) {
          bilanLoggue |= remonterContainmentsEventim(typeJob, eventimSource);
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
  /// vient de se terminer — mécanisme générique remplaçant les anciennes méthodes
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
    if (bilan.erreursHttp() > 0) {
      meterRegistry
          .counter("strapontin.worker.source.erreurs", "source", nomSource)
          .increment(bilan.erreursHttp());
    }
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
  /// bilan appels/erreurs commun porté par {@link #remonterBilan}.
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

  /// Remonte dans `logs_admin` les correspondances Eventim par containment acceptées pour le
  /// cycle qui vient de se terminer — un containment n'est pas nécessairement une erreur
  /// (Eventim ne persiste aucun identifiant, {@code rechercherAttractionId} est rappelé à chaque
  /// cycle) mais reste invisible jusque-là (log SLF4J niveau INFO uniquement) : signalé en
  /// {@code warn} pour permettre une vérification manuelle rapide plutôt qu'une acceptation
  /// silencieuse.
  ///
  /// @param typeJob le type de job de fetch (préfixe `fetch:`)
  /// @param eventim  la source Eventim dont on prélève les containments acceptés
  /// @return {@code true} si un warn a été inséré dans {@code logs_admin}
  private boolean remonterContainmentsEventim(String typeJob, EventimSource eventim) {
    List<String> containments = eventim.preleverContainmentsAcceptes();
    if (containments.isEmpty()) return false;
    logsAdminWorker.inserer(
        "warn",
        "worker",
        typeJob
            + " — "
            + containments.size()
            + " correspondance(s) Eventim par containment (#1013) : "
            + String.join("; ", containments),
        Map.of("containments", containments));
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
}
