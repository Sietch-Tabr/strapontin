/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvitesEventimTest {

  private static List<Map<String, String>> attractions(final String... noms) {
    return Arrays.stream(noms).map(n -> Map.of("name", n)).toList();
  }

  // Eventim attache les têtes d'affiche du festival (Cascadeur, Suzane, Benjamin Biolay) à toutes
  // ses soirées : seules celles que le titre nomme y jouent.
  @Test
  void depuis_festival_garderLesSeulsArtistesNommesParLeTitre() {
    assertEquals(
        List.of("Sébastien Tellier", "Dressed Like Boys"),
        InvitesEventim.depuis(
            "Sébastien Tellier + Yael Naim + Dressed like boys - Les Rendez-Vous Soniques #17",
            attractions(
                "Cascadeur",
                "Les Rendez-Vous Soniques",
                "Suzane",
                "Benjamin Biolay",
                "Yael Naim",
                "Sébastien Tellier",
                "Dressed Like Boys"),
            "Yael Naim"));
  }

  @Test
  void depuis_nomDeGroupeAvecEsperluette_resteUnSeulArtiste() {
    assertEquals(
        List.of(),
        InvitesEventim.depuis(
            "Mumford & Sons - Tournée", attractions("Mumford & Sons"), "Mumford & Sons"));
  }

  @Test
  void depuis_titreDuSeulFestival_aucunInvite() {
    assertEquals(
        List.of(),
        InvitesEventim.depuis(
            "Nancy Jazz Pulsations",
            attractions("Nancy Jazz Pulsations"),
            "Nancy Jazz Pulsations"));
  }
}
