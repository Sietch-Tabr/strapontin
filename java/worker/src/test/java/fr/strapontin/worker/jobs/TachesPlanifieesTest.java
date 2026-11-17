/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.fetch.WorkerFetchService;
import fr.strapontin.worker.maintenance.WorkerPurgePerimetreService;
import fr.strapontin.worker.maintenance.WorkerPurgeService;
import fr.strapontin.worker.maintenance.WorkerRevocationService;
import fr.strapontin.worker.maintenance.WorkerService;
import fr.strapontin.worker.maintenance.WorkerSuiviCyclesService;
import fr.strapontin.worker.planification.Planificateur;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDetail;

class TachesPlanifieesTest {

  private WorkerFetchService fetch;
  private WorkerPurgeService purge;
  private WorkerPurgePerimetreService perimetre;
  private WorkerRevocationService revocation;
  private WorkerSuiviCyclesService suiviCycles;
  private WorkerService worker;
  private TachesPlanifiees taches;

  @BeforeEach
  void preparer() {
    fetch = mock(WorkerFetchService.class);
    purge = mock(WorkerPurgeService.class);
    perimetre = mock(WorkerPurgePerimetreService.class);
    revocation = mock(WorkerRevocationService.class);
    suiviCycles = mock(WorkerSuiviCyclesService.class);
    worker = mock(WorkerService.class);
    taches = new TachesPlanifiees(fetch, purge, perimetre, revocation, suiviCycles, worker);
  }

  @Test
  void executer_chaqueTache_delegueAuBonService() {
    taches.executer("fetchSitesSalles");
    taches.executer("recalculerMatches");
    taches.executer("reconciliationNocturne");
    taches.executer("purgerSalles");
    taches.executer("purgerArtistes");
    taches.executer("purgerLogsAdmin");
    taches.executer("gererComptesInactifs");
    taches.executer("gererInvitesExpires");
    taches.executer("revoquerAdmins");
    taches.executer("suiviCyclesJobs");

    verify(fetch).executerFetchSitesSalles();
    verify(worker).executerRecalculerMatches(null);
    verify(worker).executerReconciliationNocturne();
    verify(purge).executerPurgerSalles();
    verify(purge).executerPurgerArtistes();
    verify(purge).executerPurgerLogsAdmin();
    verify(purge).executerGererComptesInactifs();
    verify(purge).executerGererInvitesExpires();
    verify(revocation).executerRevoquerAdmins();
    verify(suiviCycles).executerSuiviCycles();
  }

  @Test
  void executer_purgerConcerts_enchaineLesQuatrePurges() {
    taches.executer("purgerConcerts");

    verify(purge).executerPurgerConcerts();
    verify(purge).executerPurgerConcertsNonConfirmes();
    verify(purge).executerPurgerConcertsHorsPortee();
    verify(perimetre).executerPurgerConcertsHorsPerimetre();
  }

  @Test
  void executer_tacheInconnue_leveUneErreur() {
    assertThrows(IllegalArgumentException.class, () -> taches.executer("inconnue"));
  }

  @Test
  void exclusives_sontToutesAuRegistre() {
    assertTrue(taches.noms().containsAll(TachesPlanifiees.EXCLUSIVES));
  }

  @Test
  void tacheExclusive_portelExclusionDeConcurrence() {
    assertTrue(TacheExclusive.class.isAnnotationPresent(DisallowConcurrentExecution.class));
    assertEquals(false, TacheSimple.class.isAnnotationPresent(DisallowConcurrentExecution.class));
  }

  // Tout JobDetail du planificateur bâti sur une classe générique porte le nom d'une tâche du
  // registre, exclusive si et seulement si le registre le dit.
  @Test
  void planificateur_chaqueTacheGenerique_estAuRegistreEtExclusiveSelonLeRegistre()
      throws Exception {
    final Planificateur planificateur = new Planificateur();
    final Set<String> trouvees = new HashSet<>();
    for (final Method methode : Planificateur.class.getDeclaredMethods()) {
      if (methode.getName().startsWith("jobDetail") && methode.getParameterCount() == 0) {
        methode.setAccessible(true);
        final JobDetail detail = (JobDetail) methode.invoke(planificateur);
        final Class<?> classe = detail.getJobClass();
        if (TacheSimple.class.isAssignableFrom(classe)) {
          final String nom = detail.getKey().getName();
          trouvees.add(nom);
          assertTrue(taches.noms().contains(nom), "Tâche absente du registre : " + nom);
          assertEquals(
              TachesPlanifiees.EXCLUSIVES.contains(nom),
              TacheExclusive.class.equals(classe),
              "Exclusion de concurrence incohérente pour " + nom);
        }
      }
    }
    assertEquals(taches.noms(), trouvees, "Toute tâche du registre a un JobDetail");
  }
}
