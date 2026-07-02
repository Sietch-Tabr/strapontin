/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.config.ConfigService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  void ajouter_nomValideSansCandidat_creeNouvelleSalle() {
    UUID nouvelUuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle("olympia", "Olympia")).thenReturn(nouvelUuid);
    when(salleRepository.existePourUtilisateur("u1", nouvelUuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);

    salleService.ajouter("u1", "Olympia", null);

    verify(salleRepository).ajouterPourUtilisateur("u1", nouvelUuid);
  }

  // Régression : une salle déjà présente sous un nom incluant un suffixe descriptif absent côté
  // Wikidata/référentiel ne doit pas créer un second UUID (cas réel : "Zenith Paris - la
  // Villette" côté TM/Eventim vs "Zénith Paris" en référentiel) — même algorithme flou que
  // ReconciliateurSalles côté worker et WikidataSalleSource côté enrichissement.
  @Test
  void ajouter_variantFlouDejaPresente_reutiliseSalleExistanteSansUpsert() {
    UUID salleExistante = UUID.randomUUID();
    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", salleExistante);
    candidat.put("salle_cle", "zenithparis");
    candidat.put("nom", "Zénith Paris");
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of(candidat));
    when(salleRepository.existePourUtilisateur("u1", salleExistante)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);

    salleService.ajouter("u1", "Zenith Paris - la Villette", null);

    verify(salleRepository, never()).upsertSalle(anyString(), anyString());
    verify(salleRepository).ajouterPourUtilisateur("u1", salleExistante);
  }

  @Test
  void ajouter_salleDejaSuivie_leveRessourceDejaExistante() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle(any(), any())).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(true);

    assertThrows(RessourceDejaExistante.class, () -> salleService.ajouter("u1", "Olympia", null));
  }

  @Test
  void ajouter_capAtteint_leveErreurMetier() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle(any(), any())).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(200);

    assertThrows(ErreurMetier.class, () -> salleService.ajouter("u1", "Olympia", null));
  }

  // #718 : sélection d'une suggestion Nominatim à l'ajout — persiste osmId (salle_sources) et
  // wikidataId (salles.wikidata_id) sans attendre le job nocturne.
  @Test
  void ajouter_osmIdEtWikidataIdFournis_persisteLesDeux() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle("rockstore", "Rockstore")).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);

    salleService.ajouter("u1", "Rockstore", null, "way/12345", "Q3438008");

    verify(salleRepository).enregistrerSalleSource(uuid, "osm", "way/12345");
    verify(salleRepository).mettreAJourWikidataId(uuid, "Q3438008");
    verify(salleRepository).ajouterPourUtilisateur("u1", uuid);
  }

  // #718 : conflit sur idx_salles_wikidata_id (Q-ID déjà porté par une autre salle) — rattrapé,
  // ne doit jamais faire échouer l'ajout à la liste de suivi.
  @Test
  void ajouter_wikidataIdDejaAttribueAUneAutreSalle_neFaitPasEchouerLajout() {
    UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle("rockstore", "Rockstore")).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);
    org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("conflit"))
        .when(salleRepository)
        .mettreAJourWikidataId(uuid, "Q3438008");

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> salleService.ajouter("u1", "Rockstore", null, null, "Q3438008"));

    verify(salleRepository).ajouterPourUtilisateur("u1", uuid);
  }

  // #718 : deux résultats externes désignant la même salle (Nominatim + Ticketmaster) doivent
  // fusionner en une seule suggestion combinant osmId et tmVenueId, pas créer un doublon.
  @Test
  void fusionnerOuAjouter_candidatCorrespondantExistant_fusionneLesIdentifiants() {
    List<ResultatSalle> liste =
        new java.util.ArrayList<>(
            List.of(
                new ResultatSalle("Rockstore", "Montpellier", null, null, "osm", "way/1", "Q1")));
    ResultatSalle candidatTm = new ResultatSalle("Rockstore", null, "tm123", null, "ticketmaster");

    SalleService.fusionnerOuAjouter(liste, candidatTm);

    assertEquals(1, liste.size());
    ResultatSalle fusionne = liste.get(0);
    assertEquals("tm123", fusionne.tmVenueId());
    assertEquals("way/1", fusionne.osmId());
    assertEquals("Q1", fusionne.wikidataId());
    assertEquals("Montpellier", fusionne.ville());
  }

  @Test
  void fusionnerOuAjouter_candidatSansCorrespondance_ajouteNouvelleEntree() {
    List<ResultatSalle> liste = new java.util.ArrayList<>();
    ResultatSalle candidat =
        new ResultatSalle("Bataclan", "Paris", null, null, "osm", "way/2", "Q2");

    SalleService.fusionnerOuAjouter(liste, candidat);

    assertEquals(1, liste.size());
    assertEquals("Bataclan", liste.get(0).salle());
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
