/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link FusionSourcesUtils}.
///
/// @since 2.8.7
/// @author Kwaite
class FusionSourcesUtilsTest {

  @Test
  void laSourceEstPlusSaine_statutsIdentiquesEgaliteSante_gardeLaSource() {
    Map<String, Object> conflit =
        Map.of(
            "sante_source", 2, "sante_cible", 2, "suspect_source", false, "suspect_cible", false);

    assertTrue(FusionSourcesUtils.laSourceEstPlusSaine(conflit));
  }

  @Test
  void laSourceEstPlusSaine_sourceSuspecteCibleNonSuspecte_gardeLaCible() {
    Map<String, Object> conflit =
        Map.of("sante_source", 0, "sante_cible", 3, "suspect_source", true, "suspect_cible", false);

    assertFalse(FusionSourcesUtils.laSourceEstPlusSaine(conflit));
  }

  @Test
  void laSourceEstPlusSaine_statutsIdentiquesSourceMoinsEchecs_gardeLaSource() {
    Map<String, Object> conflit =
        Map.of(
            "sante_source", 1, "sante_cible", 3, "suspect_source", false, "suspect_cible", false);

    assertTrue(FusionSourcesUtils.laSourceEstPlusSaine(conflit));
  }
}
