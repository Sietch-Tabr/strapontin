/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.config.ConfigService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour les méthodes Digitick de {@link SalleService}.
///
/// @since 2.4.0
/// @author Kwaite
class SalleServiceTest {

  private SalleRepository salleRepository;
  private SalleService salleService;

  @BeforeEach
  void setUp() {
    salleRepository = mock(SalleRepository.class);
    salleService = new SalleService(salleRepository, mock(ConfigService.class), new ObjectMapper());
  }

  @Test
  void creerSalleDigitick_nomEtSlugValides_retourneUuid() {
    UUID attendu = UUID.randomUUID();
    when(salleRepository.upsertSalleAvecLoc(any(), any(), any(), any())).thenReturn(attendu);

    UUID resultat =
        salleService.creerSalleDigitick("L'Empreinte", "lempreinte", "Savigny-le-Temple", "77176");

    assertEquals(attendu, resultat);
    verify(salleRepository)
        .upsertSalleAvecLoc("lempreinte", "L'Empreinte", "Savigny-le-Temple", "77176");
    verify(salleRepository).enregistrerSalleSource(attendu, "digitick", "lempreinte");
  }

  @Test
  void creerSalleDigitick_sansVilleNiCp_retourneUuid() {
    UUID attendu = UUID.randomUUID();
    when(salleRepository.upsertSalleAvecLoc(any(), any(), any(), any())).thenReturn(attendu);

    UUID resultat = salleService.creerSalleDigitick("L'Olympia", "olympia", null, null);

    assertNotNull(resultat);
    verify(salleRepository).upsertSalleAvecLoc("lolympia", "L'Olympia", null, null);
  }

  @Test
  void creerSalleDigitick_salleExistante_reutiliseMemeUuid() {
    UUID existant = UUID.randomUUID();
    when(salleRepository.upsertSalleAvecLoc(any(), any(), any(), any())).thenReturn(existant);

    UUID r1 = salleService.creerSalleDigitick("L'Empreinte", "lempreinte", null, null);
    UUID r2 = salleService.creerSalleDigitick("L'Empreinte", "lempreinte", null, null);

    assertEquals(r1, r2);
  }

  @Test
  void mettreAJourSalle_appelleRepository() {
    UUID uuid = UUID.randomUUID();
    salleService.mettreAJourSalle(uuid, "Nouveau nom", "Paris", "75001");
    verify(salleRepository).mettreAJourSalle(uuid, "Nouveau nom", "Paris", "75001");
  }

  @Test
  void mettreAJourSalle_champsNuls_passesAuRepository() {
    UUID uuid = UUID.randomUUID();
    salleService.mettreAJourSalle(uuid, null, null, null);
    verify(salleRepository).mettreAJourSalle(uuid, null, null, null);
  }

  @Test
  void supprimerSalleDigitick_sansContrainte_retourneTrue() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.supprimerSalleDigitick(uuid)).thenReturn(true);
    assertTrue(salleService.supprimerSalleDigitick(uuid));
  }

  @Test
  void supprimerSalleDigitick_avecContrainte_retourneFalse() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.supprimerSalleDigitick(uuid)).thenReturn(false);
    assertFalse(salleService.supprimerSalleDigitick(uuid));
  }

  @Test
  void lireIdentifiantsSources_retourneCSV() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn("digitick:lempreinte");
    assertEquals("digitick:lempreinte", salleService.lireIdentifiantsSources(uuid));
  }

  @Test
  void lireIdentifiantsSources_sansSource_retourneNull() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn(null);
    assertNull(salleService.lireIdentifiantsSources(uuid));
  }

  @Test
  void declencherWorkerParSources_aucuneSource_nAppellePasWorker() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn(null);
    salleService.declencherWorkerParSources(uuid);
    verify(salleRepository).lireIdentifiantsSources(uuid);
  }
}
