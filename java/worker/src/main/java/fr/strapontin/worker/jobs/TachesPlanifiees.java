/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.fetch.WorkerFetchService;
import fr.strapontin.worker.maintenance.WorkerPurgePerimetreService;
import fr.strapontin.worker.maintenance.WorkerPurgeService;
import fr.strapontin.worker.maintenance.WorkerRevocationService;
import fr.strapontin.worker.maintenance.WorkerService;
import fr.strapontin.worker.maintenance.WorkerSuiviCyclesService;
import java.util.Map;
import java.util.Set;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.springframework.stereotype.Component;

/**
 * Registre des tâches planifiées qui se réduisent à l'appel d'une méthode de
 * service : une tâche de plus, c'est une entrée ici et un appel de {@link
 * #jobDetail} dans {@code Planificateur}. Les jobs qui lisent des données de
 * déclenchement (retries, identifiants, fréquence, version) gardent leur classe.
 * @since 3.1.4
 * @author Kwaite
 */
@Component
public class TachesPlanifiees {

  /**
   * Tâches qu'un seul exemplaire peut exécuter à la fois ({@code
   * @DisallowConcurrentExecution}).
   */
  public static final Set<String> EXCLUSIVES =
      Set.of("fetchSitesSalles", "recalculerMatches", "reconciliationNocturne");

  private final Map<String, Runnable> taches;

  /**
   * @param nom nom de la tâche, identité du {@code JobDetail}
   * @return le {@code JobDetail}, exclusif si le nom figure dans {@link
   *     #EXCLUSIVES}
   */
  public static JobDetail jobDetail(final String nom) {
    return JobBuilder.newJob(EXCLUSIVES.contains(nom) ? TacheExclusive.class : TacheSimple.class)
        .withIdentity(nom)
        .storeDurably()
        .build();
  }

  /**
   * @param fetch fetch des sites de salles
   * @param purge purges et gestion des comptes
   * @param perimetre purge des fiches hors périmètre
   * @param revocation révocations d'administrateurs
   * @param suiviCycles suivi des cycles de jobs
   * @param worker recalcul des matches et réconciliation nocturne
   */
  public TachesPlanifiees(
      final WorkerFetchService fetch,
      final WorkerPurgeService purge,
      final WorkerPurgePerimetreService perimetre,
      final WorkerRevocationService revocation,
      final WorkerSuiviCyclesService suiviCycles,
      final WorkerService worker) {
    this.taches =
        Map.ofEntries(
            Map.entry("fetchSitesSalles", fetch::executerFetchSitesSalles),
            Map.entry("recalculerMatches", () -> worker.executerRecalculerMatches(null)),
            Map.entry("reconciliationNocturne", worker::executerReconciliationNocturne),
            Map.entry("purgerSalles", purge::executerPurgerSalles),
            Map.entry("purgerArtistes", purge::executerPurgerArtistes),
            Map.entry("purgerLogsAdmin", purge::executerPurgerLogsAdmin),
            Map.entry("gererComptesInactifs", purge::executerGererComptesInactifs),
            Map.entry("gererInvitesExpires", purge::executerGererInvitesExpires),
            Map.entry("revoquerAdmins", revocation::executerRevoquerAdmins),
            Map.entry("suiviCyclesJobs", suiviCycles::executerSuiviCycles),
            Map.entry(
                "purgerConcerts",
                () -> {
                  purge.executerPurgerConcerts();
                  purge.executerPurgerConcertsNonConfirmes();
                  purge.executerPurgerConcertsHorsPortee();
                  perimetre.executerPurgerConcertsHorsPerimetre();
                }));
  }

  /**
   * @return les noms de toutes les tâches du registre
   */
  public Set<String> noms() {
    return taches.keySet();
  }

  /**
   * @param nom nom de la tâche (identité du {@code JobDetail})
   * @throws IllegalArgumentException si la tâche n'est pas au registre
   */
  public void executer(final String nom) {
    final Runnable tache = taches.get(nom);
    if (tache == null) {
      throw new IllegalArgumentException("Tâche planifiée inconnue : " + nom);
    }
    tache.run();
  }
}
