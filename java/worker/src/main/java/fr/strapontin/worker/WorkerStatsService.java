/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CatalogueJobs;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Statistiques et bilans de panne des jobs Quartz — extrait de
 * {@code WorkerService} , mécanisme partagé par tous les jobs (fetch, purge,
 * révocation, réconciliation), pas seulement le fetch.
 *
 * @since 2.7.0
 * @author Kwaite
 */
@Service
public class WorkerStatsService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerStatsService.class);
  private static final String SOURCE_WORKER = "worker";
  private static final String NIVEAU_WARN = "warn";

  private final List<SourceConcerts> sources;
  private final TicketmasterSource tm;
  private final ResolveurNomCanonique resolveur;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final MeterRegistry meterRegistry;
  private final SuiviChroniciteService suiviChronicite;
  private final DeriveVolumeFetchService deriveVolumeFetchService;

  /**
   * Bilan agrégé par source, cadencement/métriques et détection de repli de
   * volume — dépendances injectées par Spring (voir noms de champs).
   */
  // 8 collaborateurs Spring distincts à responsabilité unique — pas de sous-ensemble
  // regroupable sans mélanger des responsabilités indépendantes.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public WorkerStatsService(
      final List<SourceConcerts> sources,
      final TicketmasterSource tm,
      final ResolveurNomCanonique resolveur,
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final MeterRegistry meterRegistry,
      final SuiviChroniciteService suiviChronicite,
      final DeriveVolumeFetchService deriveVolumeFetchService) {
    this.sources = sources;
    this.tm = tm;
    this.resolveur = resolveur;
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.meterRegistry = meterRegistry;
    this.suiviChronicite = suiviChronicite;
    this.deriveVolumeFetchService = deriveVolumeFetchService;
  }

  /**
   * Enregistre le résultat d'un job (fetch_jobs/fetch_jobs_historique),
   * remonte les bilans de panne agrégés pour les jobs {@code fetch:}.
   * @param typeJob type de job (ex. "fetch:region", "purger:concerts")
   * @param dureeMs durée d'exécution en millisecondes
   * @param resultat bilan de persistance (insertions/mises à jour)
   * @param erreur message d'erreur, ou {@code null} si le job a réussi
   */
  public void enregistrerStatJob(
      final String typeJob,
      final long dureeMs,
      final ResultatPersistance resultat,
      final String erreur) {
    final Instant maintenant = Instant.now();
    signalerJobNonRepertorie(typeJob);
    enregistrerMetriqueDuree(typeJob, dureeMs);
    enregistrerLignesFetchJobs(typeJob, maintenant, dureeMs, resultat, erreur);
    if (typeJob.startsWith("fetch:")) {
      remonterBilansFetch(typeJob, resultat, erreur, maintenant);
    }
  }

  // Un job hors catalogue s'afficherait sous son identifiant technique :
  // signalé à chaque exécution tant qu'il n'y est pas ajouté.
  private void signalerJobNonRepertorie(final String typeJob) {
    if (CatalogueJobs.decrire(typeJob).isEmpty()) {
      LOG.warn("job absent de CatalogueJobs : {}", typeJob);
      logsAdminWorker.inserer(
          NIVEAU_WARN,
          SOURCE_WORKER,
          "job sans libellé dans le catalogue des jobs : " + typeJob,
          Map.of("typeJob", typeJob));
    }
  }

  private void enregistrerMetriqueDuree(final String typeJob, final long dureeMs) {
    Timer.builder("strapontin.worker.job.duration")
        .description("Durée d'exécution d'un job worker (fetch/purge/révocation/réconciliation)")
        .tag("type_job", typeJob)
        .register(meterRegistry)
        .record(dureeMs, TimeUnit.MILLISECONDS);
  }

  private void enregistrerLignesFetchJobs(
      final String typeJob,
      final Instant maintenant,
      final long dureeMs,
      final ResultatPersistance resultat,
      final String erreur) {
    try {
      final Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", typeJob);
      // pgjdbc n'infère pas le type SQL d'un java.time.Instant nu en paramètre
      // de bind (contrairement à la lecture via getObject/queryForObject) —
      // OffsetDateTime est le seul type JSR-310 supporté automatiquement en
      // écriture par le driver.
      params.put("now", maintenant.atOffset(ZoneOffset.UTC));
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
      LOG.warn("Impossible d'enregistrer stat job {} : {}", typeJob, e.getMessage());
    }
  }

  private void remonterBilansFetch(
      final String typeJob,
      final ResultatPersistance resultat,
      final String erreur,
      final Instant maintenant) {
    boolean bilanLoggue = false;
    for (final SourceConcerts source : sources) {
      if (source instanceof SourceAvecBilanFetch avecBilan) {
        bilanLoggue |= remonterBilan(typeJob, source.nom(), avecBilan.preleverBilan());
      }
      if (source instanceof EventimSource eventimSource) {
        bilanLoggue |= remonterContainmentsEventim(typeJob, eventimSource);
      }
    }
    bilanLoggue |= remonterBilan(typeJob, "deezer", resolveur.preleverBilan());
    bilanLoggue |= remonterBilanConcertsManquesTm(typeJob);
    if (!bilanLoggue && erreur == null && resultat.nbInseres() == 0 && resultat.nbMisAJour() == 0) {
      remonterResultatVide(typeJob, maintenant);
    }
    if (erreur == null) {
      deriveVolumeFetchService.verifier(
          typeJob, resultat.nbInseres() + resultat.nbMisAJour(), maintenant);
    }
  }

  /**
   * Exécute un job en mesurant sa durée et en enregistrant le bilan via
   * {@link #enregistrerStatJob} — motif dupliqué dans
   * WorkerPurgeService/WorkerRevocationService/WorkerFetchService.
   * @param callerLog logger de l'appelant, pour journaliser sous son nom
   * @param typeJob type de job (voir {@link #enregistrerStatJob})
   * @param travail travail à exécuter, retournant le bilan si succès
   */
  public void executerAvecStats(
      final Logger callerLog, final String typeJob, final Supplier<ResultatPersistance> travail) {
    final long debut = System.currentTimeMillis();
    try {
      final ResultatPersistance resultat = travail.get();
      enregistrerStatJob(typeJob, System.currentTimeMillis() - debut, resultat, null);
    } catch (Exception e) {
      callerLog.error("{} échoué", typeJob, e);
      enregistrerStatJob(
          typeJob,
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /**
   * Résultat d'un job pouvant se terminer normalement tout en signalant
   * un échec logique via un message d'erreur — voir
   * {@link #executerAvecStatsToleretErreur}.
   * @param resultat bilan de persistance
   * @param erreur message d'erreur, ou {@code null} si succès réel
   */
  public record ResultatJob(ResultatPersistance resultat, String erreur) {
    /**
     * Motif d'un cycle suspendu volontairement faute de quota Ticketmaster :
     * une protection, pas une panne de source.
     */
    public static final String MOTIF_QUOTA_INSUFFISANT = "quota insuffisant";

    /**
     * @param resultat bilan des sources effectivement interrogées
     * @param ticketmasterAutorise {@code false} si Ticketmaster a été sauté
     *     faute de quota
     * @return un succès, ou le bilan marqué du motif de quota insuffisant
     */
    public static ResultatJob selonQuota(
        final ResultatPersistance resultat, final boolean ticketmasterAutorise) {
      return new ResultatJob(resultat, ticketmasterAutorise ? null : MOTIF_QUOTA_INSUFFISANT);
    }

    /**
     * @param resultat bilan de persistance d'un job réellement réussi
     * @return un résultat sans erreur
     */
    public static ResultatJob succes(final ResultatPersistance resultat) {
      return new ResultatJob(resultat, null);
    }
  }

  /**
   * Variante de {@link #executerAvecStats} pour les jobs pouvant retourner
   * un cas métier (ex. "quota insuffisant") plutôt qu'une exception.
   * @param callerLog logger de l'appelant, pour journaliser sous son nom
   * @param typeJob type de job (voir {@link #enregistrerStatJob})
   * @param travail travail à exécuter, retournant bilan + erreur optionnelle
   */
  public void executerAvecStatsToleretErreur(
      final Logger callerLog, final String typeJob, final Supplier<ResultatJob> travail) {
    final long debut = System.currentTimeMillis();
    try {
      final ResultatJob rj = travail.get();
      enregistrerStatJob(typeJob, System.currentTimeMillis() - debut, rj.resultat(), rj.erreur());
    } catch (Exception e) {
      callerLog.error("{} échoué", typeJob, e);
      enregistrerStatJob(
          typeJob,
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /**
   * Remonte le bilan agrégé d'appels/erreurs d'une source (panne totale
   * en error, échec partiel en warn — seuil BilanFetch).
   * @param typeJob le type de job de fetch (préfixe fetch:)
   * @param nomSource nom de la source, capitalisé pour l'affichage
   * @param bilan bilan prélevé pour cette source depuis le dernier cycle
   * @return {@code true} si une entrée a été insérée dans logs_admin
   */
  private boolean remonterBilan(
      final String typeJob, final String nomSource, final BilanFetch bilan) {
    if (bilan == null || bilan.appels() == 0) {
      return false;
    }
    if (bilan.erreursHttp() > 0) {
      meterRegistry
          .counter("strapontin.worker.source.erreurs", "source", nomSource)
          .increment(bilan.erreursHttp());
    }
    final int streak =
        suiviChronicite.enregistrerCycle(typeJob + "|" + nomSource, bilan.erreursHttp() > 0);
    return bilan
        .evaluer(typeJob, capitaliser(nomSource), BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT)
        .map(
            entree -> {
              final String message = suiviChronicite.enrichirSiChronique(entree.message(), streak);
              logsAdminWorker.inserer(entree.niveau(), SOURCE_WORKER, message, entree.meta());
              return true;
            })
        .orElse(false);
  }

  private static String capitaliser(final String nom) {
    return nom.isEmpty() ? nom : Character.toUpperCase(nom.charAt(0)) + nom.substring(1);
  }

  /**
   * Remonte le nombre de concerts TM non récupérés (pagination
   * plafonnée) — métrique spécifique à TM, distincte du bilan
   * appels/erreurs commun porté par {@link #remonterBilan}.
   * @param typeJob le type de job de fetch (préfixe fetch:)
   * @return {@code true} si un warn a été inséré dans logs_admin
   */
  private boolean remonterBilanConcertsManquesTm(final String typeJob) {
    final int concertsManques = tm.preleverConcertsManques();
    if (concertsManques == 0) {
      return false;
    }
    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_WORKER,
        typeJob + " — " + concertsManques + " concert(s) TM non récupéré(s) (pagination plafonnée)",
        Map.of("manques", concertsManques));
    return true;
  }

  /**
   * Remonte les correspondances Eventim par containment acceptées — pas
   * nécessairement une erreur, mais invisible jusque-là (log INFO seul) :
   * signalé en warn pour vérification manuelle rapide.
   * @param typeJob le type de job de fetch (préfixe fetch:)
   * @param eventim la source Eventim dont on prélève les containments
   * @return {@code true} si un warn a été inséré dans logs_admin
   */
  private boolean remonterContainmentsEventim(final String typeJob, final EventimSource eventim) {
    final List<String> containments = eventim.preleverContainmentsAcceptes();
    if (containments.isEmpty()) {
      return false;
    }
    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_WORKER,
        typeJob
            + " — "
            + containments.size()
            + " correspondance(s) Eventim par containment : "
            + String.join("; ", containments),
        Map.of("containments", containments));
    return true;
  }

  /**
   * Remonte dans {@code logs_admin} un warn de résultat vide (nb_inseres +
   * nb_mis_a_jour = 0) pour un fetch qui s'est terminé sans erreur mais sans
   * aucun concert persisté.
   *
   * @param typeJob le type de job de fetch
   * @param execLe l'horodatage de l'exécution
   */
  private void remonterResultatVide(final String typeJob, final Instant execLe) {
    try {
      final Map<String, Object> params = new LinkedHashMap<>();
      params.put("ts", Timestamp.from(execLe));
      params.put("niveau", NIVEAU_WARN);
      params.put("source", SOURCE_WORKER);
      params.put("message", typeJob + " — 0 concert inséré ou mis à jour (résultat vide)");
      params.put("meta", "{}");
      jdbc.update(
          """
          INSERT INTO logs_admin (ts, niveau, source, message, meta)
          VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
          """,
          params);
    } catch (Exception e) {
      LOG.warn("Impossible de remonter le résultat vide pour {} : {}", typeJob, e.getMessage());
    }
  }
}
