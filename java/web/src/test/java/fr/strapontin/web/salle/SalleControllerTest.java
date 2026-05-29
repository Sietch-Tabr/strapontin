/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link SalleController}.
///
/// <p>Couvre la normalisation de noms de salles et la détection des doublons de suivi.
///
/// @since 2.0.5
/// @author Kwaite
class SalleControllerTest {

  @Test
  void normaliserSalleCle_nomStandard_retourneAlphanumeriqueMinuscule() {
    assertEquals("olympia", SalleController.normaliserSalleCle("Olympia"));
  }

  @Test
  void normaliserSalleCle_accentsEtPonctuation_retireCaracteresSpeciaux() {
    assertEquals("zenithdemontbrun", SalleController.normaliserSalleCle("Zénith de Montbrun"));
  }

  @Test
  void normaliserSalleCle_casseMixte_resultatIdentique() {
    assertEquals(
        SalleController.normaliserSalleCle("BATACLAN"),
        SalleController.normaliserSalleCle("bataclan"));
  }

  @Test
  void dejaLaSuivie_nomIdentique_retourneVrai() {
    var salles =
        List.of(new SalleController.SalleUtilisateur("olympia", "Olympia", null, Instant.EPOCH));
    String rechercheNorm = SalleController.normaliserSalleCle("Olympia");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleController.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertTrue(dejaLaSuivie);
  }

  @Test
  void dejaLaSuivie_nomAvecAccentEtCasseDifferente_retourneVrai() {
    var salles =
        List.of(new SalleController.SalleUtilisateur("zenith", "Zénith", null, Instant.EPOCH));
    String rechercheNorm = SalleController.normaliserSalleCle("zenith");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleController.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertTrue(dejaLaSuivie);
  }

  @Test
  void dejaLaSuivie_nomDifferent_retourneFaux() {
    var salles =
        List.of(new SalleController.SalleUtilisateur("olympia", "Olympia", null, Instant.EPOCH));
    String rechercheNorm = SalleController.normaliserSalleCle("Bataclan");
    boolean dejaLaSuivie =
        salles.stream()
            .anyMatch(s -> SalleController.normaliserSalleCle(s.nom()).equals(rechercheNorm));
    assertFalse(dejaLaSuivie);
  }
}
