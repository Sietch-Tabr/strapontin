/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EligibiliteResolutionDeezerTest {

  @Test
  void estEligible_classificationMusicale_estEligible() {
    assertTrue(EligibiliteResolutionDeezer.estEligible("music", "ticketmaster"));
  }

  @Test
  void estEligible_classificationTheatre_nEstPasEligible() {
    assertFalse(EligibiliteResolutionDeezer.estEligible("theatre", "ticketmaster"));
  }

  @Test
  void estEligible_classificationDivers_nEstPasEligible() {
    assertFalse(EligibiliteResolutionDeezer.estEligible("divers", "ticketmaster"));
  }

  @Test
  void estEligible_classificationSports_nEstPasEligible() {
    assertFalse(EligibiliteResolutionDeezer.estEligible("sports", "ticketmaster"));
  }

  @Test
  void estEligible_classificationNullSourceTicketmaster_estEligible() {
    assertTrue(EligibiliteResolutionDeezer.estEligible(null, "ticketmaster"));
  }

  @Test
  void estEligible_classificationNullSourceEventim_nEstPasEligible() {
    // Sans classification, Eventim ne garantit rien sur la nature musicale du concert.
    assertFalse(EligibiliteResolutionDeezer.estEligible(null, "eventim"));
  }

  @Test
  void estEligible_classificationVideSourceEventim_nEstPasEligible() {
    assertFalse(EligibiliteResolutionDeezer.estEligible("", "eventim"));
  }

  @Test
  void estEligible_classificationMusicSourceEventim_estEligible() {
    assertTrue(EligibiliteResolutionDeezer.estEligible("music", "eventim"));
  }
}
