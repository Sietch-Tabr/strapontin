/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.BudgetRecherchesExternes.Service;
import fr.strapontin.web.admin.LogAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetRecherchesExternesTest {

  private LogAdminService logAdminService;
  private BudgetRecherchesExternes budget;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    budget = new BudgetRecherchesExternes(logAdminService);
  }

  @Test
  void consommer_plafondNominatimAtteint_refuseEtJournaliseUneSeuleFois() {
    for (int i = 0; i < 20; i++) {
      assertTrue(budget.consommer(Service.NOMINATIM));
    }

    assertFalse(budget.consommer(Service.NOMINATIM));
    assertFalse(budget.consommer(Service.NOMINATIM));

    verify(logAdminService, times(1))
        .logAdmin(eq("warn"), eq("recherche-externe"), contains("Nominatim"));
  }

  @Test
  void consommer_servicesDifferents_plafondsIndependants() {
    for (int i = 0; i < 20; i++) {
      budget.consommer(Service.NOMINATIM);
    }

    assertTrue(budget.consommer(Service.WIKIDATA));
    assertTrue(budget.consommer(Service.DEEZER));
  }

  @Test
  void autoriserUtilisateur_seiziemeRechercheDansLaMinute_estRefusee() {
    for (int i = 0; i < 15; i++) {
      assertTrue(budget.autoriserUtilisateur("u1"));
    }

    assertFalse(budget.autoriserUtilisateur("u1"));
    assertTrue(budget.autoriserUtilisateur("u2"));
  }
}
