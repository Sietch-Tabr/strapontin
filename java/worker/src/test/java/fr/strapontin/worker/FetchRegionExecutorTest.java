/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.core.Classement;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.LieuBrut;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Classements dont l'absence peut être constatée par la récupération par région : seuls ceux que
 * la recherche Ticketmaster demande.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class FetchRegionExecutorTest {

  private static ConcertBrut concert(
      final String source, final String classification, final String genre, final String sous) {
    return new ConcertBrut(
        "Artiste",
        null,
        "2027-01-01",
        null,
        new LieuBrut("Salle", "Paris", "75", null, null, null),
        null,
        source,
        new Classement(classification, genre, sous),
        null,
        null);
  }

  @Test
  void classementsDemandesEtRenvoyes_musiqueOperaEtMusical_sontRetenus() {
    final Set<String> cles =
        FetchRegionExecutor.classementsDemandesEtRenvoyes(
            List.of(
                concert("ticketmaster", "music", "Rock", "Pop"),
                concert("ticketmaster", "theatre", "Opéra", "Opéra"),
                concert("ticketmaster", "theatre", "Théâtre", "Musical")),
            "ticketmaster");

    assertEquals(Set.of("music|Rock|Pop", "theatre|Opéra|Opéra", "theatre|Théâtre|Musical"), cles);
  }

  @Test
  void classementsDemandesEtRenvoyes_humourSpectaculaireEtCirque_sontEcartes() {
    // Renvoyés parce qu'ils portent aussi une classification demandée, mais jamais demandés :
    // les fiches de même triplet ne doivent pas gagner d'absence.
    final Set<String> cles =
        FetchRegionExecutor.classementsDemandesEtRenvoyes(
            List.of(
                concert("ticketmaster", "theatre", "Humour", "Humour"),
                concert("ticketmaster", "theatre", "Spectaculaire", "Spectaculaire"),
                concert("ticketmaster", "theatre", "Cirque et Numéros spéciaux", "Cirque")),
            "ticketmaster");

    assertEquals(Set.of(), cles);
  }

  @Test
  void classementsDemandesEtRenvoyes_autreSource_estIgnoree() {
    final Set<String> cles =
        FetchRegionExecutor.classementsDemandesEtRenvoyes(
            List.of(concert("eventim", "music", "Rock", "Pop")), "ticketmaster");

    assertEquals(Set.of(), cles);
  }
}
