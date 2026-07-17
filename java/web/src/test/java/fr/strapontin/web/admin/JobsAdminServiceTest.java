/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link JobsAdminService} (#916, extrait de {@code AdminService}).
///
/// @since 2.1.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
class JobsAdminServiceTest {

  @Test
  void completerAvecJobsJamaisExecutes_jobPlanifieSansHistorique_ajouteEntreeSynthetique() {
    List<StatJob> statsConnus =
        List.of(new StatJob("purger:concerts", Instant.EPOCH, 10, 1, 2, null, null));
    Map<String, Instant> prochainesExec =
        Map.of(
            "purger:concerts", Instant.EPOCH,
            "reconciliation:nocturne", Instant.MAX);

    List<StatJob> resultat =
        JobsAdminService.completerAvecJobsJamaisExecutes(statsConnus, prochainesExec);

    assertEquals(2, resultat.size());
    StatJob jamaisExecute =
        resultat.stream()
            .filter(s -> s.typeJob().equals("reconciliation:nocturne"))
            .findFirst()
            .orElseThrow();
    assertEquals(null, jamaisExecute.derniereExec());
    assertEquals(Instant.MAX, jamaisExecute.prochaineExec());
  }

  @Test
  void completerAvecJobsJamaisExecutes_tousLesJobsConnus_neDupliquePas() {
    List<StatJob> statsConnus =
        List.of(new StatJob("purger:concerts", Instant.EPOCH, 10, 1, 2, null, null));
    Map<String, Instant> prochainesExec = Map.of("purger:concerts", Instant.EPOCH);

    List<StatJob> resultat =
        JobsAdminService.completerAvecJobsJamaisExecutes(statsConnus, prochainesExec);

    assertEquals(1, resultat.size());
  }
}
