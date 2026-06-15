/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Tests unitaires de la normalisation et de la détection des doublons pour les salles.
///
/// <p>Couvre {@link SalleService#normaliserSalleCle(String)} et la logique de suivi.
///
/// @since 2.0.5
/// @author Kwaite
class SalleControllerTest {

  @Test
  void normaliserSalleCle_nomStandard_retourneAlphanumeriqueMinuscule() {
    assertEquals("olympia", SalleService.normaliserSalleCle("Olympia"));
  }

  @Test
  void normaliserSalleCle_accentsEtPonctuation_retireCaracteresSpeciaux() {
    assertEquals("zenithdemontbrun", SalleService.normaliserSalleCle("Zénith de Montbrun"));
  }

  @Test
  void normaliserSalleCle_apostrophe_retireApostrophe() {
    assertEquals("lempreinte", SalleService.normaliserSalleCle("l'empreinte"));
  }

  @Test
  void normaliserSalleCle_tiret_retireTiret() {
    assertEquals("zenitharena", SalleService.normaliserSalleCle("zenith-arena"));
  }

  @Test
  void normaliserSalleCle_casseMixte_resultatIdentique() {
    assertEquals(
        SalleService.normaliserSalleCle("BATACLAN"), SalleService.normaliserSalleCle("bataclan"));
  }

  @Test
  void dejaLaSuivie_nomIdentique_retourneVrai() {
    var salles = List.of(new SalleUtilisateur("olympia", "Olympia", null, Instant.EPOCH));
    String rechercheNorm = SalleService.normaliserSalleCle("Olympia");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleService.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertTrue(dejaLaSuivie);
  }

  @Test
  void dejaLaSuivie_nomAvecAccentEtCasseDifferente_retourneVrai() {
    var salles = List.of(new SalleUtilisateur("zenith", "Zénith", null, Instant.EPOCH));
    String rechercheNorm = SalleService.normaliserSalleCle("zenith");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleService.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertTrue(dejaLaSuivie);
  }

  @Test
  void dejaLaSuivie_nomDifferent_retourneFaux() {
    var salles = List.of(new SalleUtilisateur("olympia", "Olympia", null, Instant.EPOCH));
    String rechercheNorm = SalleService.normaliserSalleCle("Bataclan");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleService.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertFalse(dejaLaSuivie);
  }
}
