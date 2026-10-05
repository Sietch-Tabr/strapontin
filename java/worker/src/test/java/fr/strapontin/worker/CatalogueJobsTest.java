/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.core.CatalogueJobs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Chaque job planifié du worker a un nom et un thème dans {@link CatalogueJobs}, faute de quoi il
 * apparaîtrait sous son identifiant technique dans le tableau de bord admin.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class CatalogueJobsTest {

  @Test
  void catalogue_toutJobPlanifie_estRepertorie() {
    final List<String> absents =
        NomsJobs.PAR_CLE_QUARTZ.values().stream()
            .filter(type -> CatalogueJobs.decrire(type).isEmpty())
            .sorted()
            .toList();

    assertEquals(List.of(), absents);
  }

  @Test
  void catalogue_sousEtapesEcrivantLeurPropreLigne_sontRepertoriees() {
    for (final String type :
        List.of("purger:concerts-non-confirmes", "purger:concerts-hors-portee")) {
      assertEquals(type, CatalogueJobs.decrire(type).orElseThrow().type());
    }
  }
}
