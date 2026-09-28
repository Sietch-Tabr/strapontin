/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.admin.LogAdminService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.RestClient;

/**
 * Tests unitaires de {@link SalleService}.
 *
 * @since 2.4.0
 * @author Kwaite
 */
class SalleServiceTest {

  private SalleRepository salleRepository;
  private LogAdminService logAdminService;
  private SalleRechercheNominatimService salleRechercheNominatimService;
  private SalleRechercheTicketmasterService salleRechercheTicketmasterService;
  private SalleService salleService;

  @BeforeEach
  void setUp() {
    salleRepository = mock(SalleRepository.class);
    logAdminService = mock(LogAdminService.class);
    salleRechercheNominatimService = mock(SalleRechercheNominatimService.class);
    salleRechercheTicketmasterService = mock(SalleRechercheTicketmasterService.class);
    // stubé pour lever une exception nommée et réaliste (worker injoignable) plutôt que de
    // laisser post() non stubé retourner null — une NPE accidentelle dans le fire-and-forget de
    // WorkerTriggerUtils.declencherWorkerAsync serait sinon indistinguable d'un vrai bug au
    // premier coup d'œil dans les logs de test.
    final RestClient restClient = mock(RestClient.class);
    when(restClient.post()).thenThrow(new RuntimeException("worker injoignable"));
    salleService =
        new SalleService(
            salleRepository,
            restClient,
            logAdminService,
            salleRechercheNominatimService,
            salleRechercheTicketmasterService);
    salleService.setSelf(salleService);
  }

  @Test
  void ajouter_nomValideSansCandidat_creeNouvelleSalle() {
    final UUID nouvelUuid = UUID.randomUUID();
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
    final UUID salleExistante = UUID.randomUUID();
    final Map<String, Object> candidat = new HashMap<>();
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
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle(any(), any())).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(true);

    assertThrows(RessourceDejaExistante.class, () -> salleService.ajouter("u1", "Olympia", null));
  }

  @Test
  void ajouter_capAtteint_leveErreurMetier() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle(any(), any())).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(200);

    assertThrows(ErreurMetier.class, () -> salleService.ajouter("u1", "Olympia", null));
  }

  // sélection d'une suggestion Nominatim à l'ajout — persiste osmId (salle_sources) et
  // wikidataId (salles.wikidata_id) sans attendre le job nocturne.
  @Test
  void ajouter_osmIdEtWikidataIdFournis_persisteLesDeux() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle("rockstore", "Rockstore")).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);

    salleService.ajouter(
        new SalleService.DonneesAjoutSalle("u1", "Rockstore", null, "way/12345", "Q3438008"));

    verify(salleRepository).enregistrerSalleSource(uuid, "osm", "way/12345");
    verify(salleRepository).mettreAJourWikidataId(uuid, "Q3438008");
    verify(salleRepository).ajouterPourUtilisateur("u1", uuid);
  }

  // conflit sur idx_salles_wikidata_id (Q-ID déjà porté par une autre salle) — rattrapé,
  // ne doit jamais faire échouer l'ajout à la liste de suivi.
  @Test
  void ajouter_wikidataIdDejaAttribueAUneAutreSalle_neFaitPasEchouerLajout() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.chercherCandidatsParCle(any())).thenReturn(List.of());
    when(salleRepository.upsertSalle("rockstore", "Rockstore")).thenReturn(uuid);
    when(salleRepository.existePourUtilisateur("u1", uuid)).thenReturn(false);
    when(salleRepository.compterPourUtilisateur("u1")).thenReturn(0);
    org.mockito.Mockito.doThrow(new DuplicateKeyException("conflit"))
        .when(salleRepository)
        .mettreAJourWikidataId(uuid, "Q3438008");

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            salleService.ajouter(
                new SalleService.DonneesAjoutSalle("u1", "Rockstore", null, null, "Q3438008")));

    verify(salleRepository).ajouterPourUtilisateur("u1", uuid);
  }

  // deux résultats externes désignant la même salle (Nominatim + Ticketmaster) doivent
  // fusionner en une seule suggestion combinant osmId et tmVenueId, pas créer un doublon.
  @Test
  void fusionnerOuAjouter_candidatCorrespondantExistant_fusionneLesIdentifiants() {
    final List<ResultatSalle> liste =
        new ArrayList<>(
            List.of(new ResultatSalle("Rockstore", "Montpellier", null, "osm", "way/1", "Q1")));
    final ResultatSalle candidatTm = new ResultatSalle("Rockstore", null, "tm123", "ticketmaster");

    SalleSuggestions.fusionnerOuAjouter(liste, candidatTm);

    assertEquals(1, liste.size());
    final ResultatSalle fusionne = liste.get(0);
    assertEquals("tm123", fusionne.tmVenueId());
    assertEquals("way/1", fusionne.osmId());
    assertEquals("Q1", fusionne.wikidataId());
    assertEquals("Montpellier", fusionne.ville());
  }

  @Test
  void fusionnerOuAjouter_candidatSansCorrespondance_ajouteNouvelleEntree() {
    final List<ResultatSalle> liste = new ArrayList<>();
    final ResultatSalle candidat =
        new ResultatSalle("Bataclan", "Paris", null, "osm", "way/2", "Q2");

    SalleSuggestions.fusionnerOuAjouter(liste, candidat);

    assertEquals(1, liste.size());
    assertEquals("Bataclan", liste.get(0).salle());
  }

  @Test
  void supprimer_delegueAuRepository_sansException() {
    final UUID salleId = UUID.randomUUID();
    when(salleRepository.supprimerPourUtilisateur("u1", salleId)).thenReturn(1);

    assertDoesNotThrow(() -> salleService.supprimer("u1", salleId));

    verify(salleRepository).supprimerPourUtilisateur("u1", salleId);
  }

  // un DELETE no-op (salle déjà retirée, jamais suivie, ou appartenant à un autre
  // utilisateur) ne doit produire aucune entrée d'audit trompeuse "salle supprimée".
  @Test
  void supprimer_salleNonSuivie_neLogguePas() {
    final UUID salleId = UUID.randomUUID();
    when(salleRepository.supprimerPourUtilisateur("u1", salleId)).thenReturn(0);

    salleService.supprimer("u1", salleId);

    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void supprimer_salleReellementSupprimee_logguePourAudit() {
    final UUID salleId = UUID.randomUUID();
    when(salleRepository.supprimerPourUtilisateur("u1", salleId)).thenReturn(1);

    salleService.supprimer("u1", salleId);

    verify(logAdminService).logAdmin(eq("warn"), eq("web"), anyString(), anyMap());
  }

  @Test
  void lireIdentifiantsSources_retourneCSV() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn("eventim:internal:lempreinte");
    assertEquals("eventim:internal:lempreinte", salleService.lireIdentifiantsSources(uuid));
  }

  @Test
  void lireIdentifiantsSources_sansSource_retourneNull() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn(null);
    assertNull(salleService.lireIdentifiantsSources(uuid));
  }

  @Test
  void declencherWorkerParSources_aucuneSource_nAppellePasWorker() {
    final UUID uuid = UUID.randomUUID();
    when(salleRepository.lireIdentifiantsSources(uuid)).thenReturn(null);
    salleService.declencherWorkerParSources(uuid);
    verify(salleRepository).lireIdentifiantsSources(uuid);
  }

  // "zenith" doit être détecté comme correspondant partiellement à "zenithparis" déjà
  // suivie — même correctif que ArtisteService.contientCorrespondancePartielle.
  @Test
  void contientCorrespondancePartielle_rechercheSubstringDuneSalleSuivie_retourneTrue() {
    assertTrue(SalleSuggestions.contientCorrespondancePartielle("zenith", Set.of("zenithparis")));
  }

  @Test
  void contientCorrespondancePartielle_correspondanceExacte_retourneTrue() {
    assertTrue(
        SalleSuggestions.contientCorrespondancePartielle("zenithparis", Set.of("zenithparis")));
  }

  @Test
  void contientCorrespondancePartielle_aucuneCorrespondance_retourneFalse() {
    assertFalse(SalleSuggestions.contientCorrespondancePartielle("olympia", Set.of("zenithparis")));
  }

  // déplacés depuis l'ancien SalleControllerTest, qui ne testait en réalité pas
  // SalleController mais cette méthode statique.
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

  // Nominatim et Ticketmaster partent en parallèle dès que la recherche en base ne suffit pas —
  // un enchaînement séquentiel (Nominatim puis Ticketmaster seulement si toujours insuffisant)
  // pouvait approcher la somme des deux timeouts réseau dans le pire cas, constaté en E2E
  // (mobile-safari).
  @Test
  void lister_baseInsuffisante_appelleNominatimEtTM() {
    when(salleRepository.listerPourUtilisateur("user1")).thenReturn(List.of());
    when(salleRepository.rechercherEnBase(anyString())).thenReturn(List.of());
    when(salleRechercheNominatimService.rechercher("Bataclan"))
        .thenReturn(List.of(new ResultatSalle("Le Bataclan", "Paris", null, "osm")));
    when(salleRechercheTicketmasterService.rechercher("Bataclan")).thenReturn(List.of());

    final var reponse = salleService.lister("user1", "Bataclan");

    assertEquals(1, reponse.resultats().size());
    verify(salleRechercheNominatimService).rechercher("Bataclan");
    verify(salleRechercheTicketmasterService).rechercher("Bataclan");
  }

  // Avant le passage en parallèle, Ticketmaster n'était interrogé que si Nominatim seul ne
  // suffisait pas (< 2 résultats) — désormais les deux partent ensemble dès que la recherche en
  // base seule est insuffisante, Ticketmaster est donc appelé même si Nominatim seul atteint
  // déjà le seuil.
  @Test
  void lister_nominatimSeulAtteintLeSeuil_appelleQuandMemeTMEnParallele() {
    when(salleRepository.listerPourUtilisateur("user1")).thenReturn(List.of());
    when(salleRepository.rechercherEnBase(anyString())).thenReturn(List.of());
    when(salleRechercheNominatimService.rechercher("terme"))
        .thenReturn(
            List.of(
                new ResultatSalle("A", "Paris", null, "osm"),
                new ResultatSalle("B", "Paris", null, "osm")));
    when(salleRechercheTicketmasterService.rechercher("terme")).thenReturn(List.of());

    salleService.lister("user1", "terme");

    verify(salleRechercheTicketmasterService).rechercher("terme");
  }
}
