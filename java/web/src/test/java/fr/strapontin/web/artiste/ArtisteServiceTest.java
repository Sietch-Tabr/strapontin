/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.sources.http.IdentiteHttp;
import fr.strapontin.web.BudgetRecherchesExternes;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.admin.LogAdminService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires de {@link ArtisteService}.
 *
 * <p>Couvre la logique métier de {@code ajouter()} : détection de doublon, cap 500, insertion.
 *
 * @since 2.1.2
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class ArtisteServiceTest {

  private static final IdentiteHttp IDENTITE =
      new IdentiteHttp("test", "https://exemple.test/projet", () -> "");

  @Mock private ArtisteRepository artisteRepository;
  @Mock private ObjectMapper objectMapper;
  @Mock private RestClient restClient;
  @Mock private LogAdminService logAdminService;

  private ArtisteService service;

  @BeforeEach
  void setUp() {
    service =
        new ArtisteService(
            artisteRepository,
            objectMapper,
            restClient,
            logAdminService,
            new BudgetRecherchesExternes(logAdminService),
            IDENTITE);
  }

  @Test
  void ajouter_artisteDejaPresent_leveRessourceDejaExistante() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(true);

    assertThrows(RessourceDejaExistante.class, () -> service.ajouter("u1", "Radiohead"));
    verify(artisteRepository, never()).ajouter(anyString(), anyString(), any());
  }

  @Test
  void ajouter_capAtteint_leveErreurMetier() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(500);

    assertThrows(ErreurMetier.class, () -> service.ajouter("u1", "Radiohead"));
    verify(artisteRepository, never()).ajouter(anyString(), anyString(), any());
  }

  @Test
  void ajouter_nomTribute_leveErreurMetierSansAppelerLeRepository() {
    assertThrows(ErreurMetier.class, () -> service.ajouter("u1", "Tribute to Nina Simone"));
    verifyNoInteractions(artisteRepository);
  }

  @Test
  void ajouter_nomValide_insereDansLaBase() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(42);

    service.ajouter("u1", "Radiohead");

    verify(artisteRepository).ajouter("u1", "Radiohead", artisteId);
  }

  // Régression : "Krash Karma" / "KrashKarma" ont historiquement créé deux artistes distincts en
  // base (fusion manuelle nécessaire, V28__fusionner_doublon_artiste_krashkarma.sql) faute de
  // tolérance floue à l'ajout. La résolution doit désormais réutiliser l'UUID existant sans créer
  // de doublon ni appeler upsertArtiste (qui écraserait le nom affiché global).
  @Test
  void ajouter_variantFlouDejaPresent_reutiliseArtisteExistantSansUpsert() {
    final UUID artisteExistant = UUID.randomUUID();
    final Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", artisteExistant);
    candidat.put("nom_norm", "krash karma");
    when(artisteRepository.chercherCandidatsParTokens(any(), anyString()))
        .thenReturn(List.of(candidat));
    when(artisteRepository.existePourUtilisateur("u1", artisteExistant)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    service.ajouter("u1", "KrashKarma");

    verify(artisteRepository, never()).upsertArtiste(anyString(), anyString());
    verify(artisteRepository).ajouter("u1", "KrashKarma", artisteExistant);
  }

  @Test
  void ajouter_correspondanceExacteParmiLesCandidats_passeParUpsertPourMettreAJourLeNom() {
    final UUID artisteId = UUID.randomUUID();
    final Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", artisteId);
    candidat.put("nom_norm", "radiohead");
    when(artisteRepository.chercherCandidatsParTokens(any(), anyString()))
        .thenReturn(List.of(candidat));
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    service.ajouter("u1", "Radiohead");

    verify(artisteRepository).upsertArtiste("Radiohead", "radiohead");
  }

  // sélection d'une suggestion Wikidata à l'ajout — résout le P2722 (Deezer ID) et
  // persiste les deux sources sans attendre le job nocturne. resoudreDeezerId est substituée
  // (Mockito.spy) pour éviter un appel réseau réel vers Wikidata en test unitaire.
  @Test
  void ajouter_wikidataIdFourni_resoutDeezerIdEtPersisteLesSources() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Daft Punk", "daft punk")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    final ArtisteService spy = Mockito.spy(service);
    doReturn("27").when(spy).resoudreDeezerId("Q167023");

    spy.ajouter("u1", "Daft Punk", "Q167023");

    verify(artisteRepository).enregistrerArtisteSource(artisteId, "wikidata", "Q167023");
    verify(artisteRepository).enregistrerArtisteSource(artisteId, "deezer", "27");
    verify(artisteRepository).mettreAJourIdsWikidata(artisteId, "Q167023", "27");
  }

  // conflit sur idx_artistes_wikidata_id (Q-ID déjà porté par un autre artiste) — rattrapé
  // et loggé, ne doit jamais faire échouer l'ajout à la liste de suivi.
  @Test
  void ajouter_wikidataIdDejaAttribueAUnAutreArtiste_neFaitPasEchouerLajout() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Daft Punk", "daft punk")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);
    doThrow(new DuplicateKeyException("conflit"))
        .when(artisteRepository)
        .enregistrerArtisteSource(artisteId, "wikidata", "Q167023");

    assertDoesNotThrow(() -> service.ajouter("u1", "Daft Punk", "Q167023"));

    verify(artisteRepository).ajouter("u1", "Daft Punk", artisteId);
    verify(logAdminService)
        .logAdmin(eq("warn"), eq("enrichissement-wikidata"), anyString(), anyMap());
    verify(artisteRepository, never()).mettreAJourIdsWikidata(any(), any(), any());
  }

  @Test
  void supprimer_delegueAuRepository_sansException() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(1);

    assertDoesNotThrow(() -> service.supprimer("u1", artisteId));

    verify(artisteRepository).supprimer("u1", artisteId);
  }

  // un DELETE no-op (artiste déjà retiré, jamais suivi, ou appartenant à un autre
  // utilisateur) ne doit produire aucune entrée d'audit trompeuse "artiste supprimé".
  @Test
  void supprimer_artisteNonSuivi_neLogguePas() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(0);

    service.supprimer("u1", artisteId);

    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void supprimer_artisteReellementSupprime_logguePourAudit() {
    final UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(1);

    service.supprimer("u1", artisteId);

    verify(logAdminService).logAdmin(eq("warn"), eq("web"), anyString(), anyMap());
  }

  // "Yael" doit être détecté comme correspondant partiellement à "yaelnaim" déjà suivi —
  // avant correctif, seule une égalité stricte était testée et l'utilisateur voyait "Aucun
  // résultat" alors que l'artiste était déjà suivi.
  @Test
  void contientCorrespondancePartielle_rechercheSubstringDunNomSuivi_retourneTrue() {
    assertTrue(ArtisteService.contientCorrespondancePartielle("yael", Set.of("yaelnaim")));
  }

  @Test
  void contientCorrespondancePartielle_correspondanceExacte_retourneTrue() {
    assertTrue(ArtisteService.contientCorrespondancePartielle("yaelnaim", Set.of("yaelnaim")));
  }

  @Test
  void contientCorrespondancePartielle_aucuneCorrespondance_retourneFalse() {
    assertFalse(ArtisteService.contientCorrespondancePartielle("radiohead", Set.of("yaelnaim")));
  }

  // « Krash Karma » (Wikidata) et « Krashkarma » (Deezer) ne diffèrent que par l'espace : un seul
  // résultat, celui de Wikidata, qui porte la description.
  @Test
  void lister_variantesDEspacementEntreSources_neGardeQueLaPremiere() {
    when(artisteRepository.listerPourUtilisateur("user1")).thenReturn(List.of());
    when(artisteRepository.rechercherEnBase(anyString())).thenReturn(List.of());

    final ArtisteService espion = Mockito.spy(service);
    doReturn(List.of(new SuggestionArtiste("Krash Karma", "wikidata", "band", "Q684361")))
        .when(espion)
        .rechercherWikidata("Krash Karma");
    doReturn(
            List.of(
                new SuggestionArtiste("Krashkarma", "deezer", null, null, 1385),
                new SuggestionArtiste("Kash Karma", "deezer", null, null, 12)))
        .when(espion)
        .rechercherDeezer("Krash Karma");

    final var reponse = espion.lister("user1", "Krash Karma");

    assertEquals(
        List.of("Krash Karma", "Kash Karma"),
        reponse.suggestions().stream().map(SuggestionArtiste::nom).toList());
  }

  @Test
  void lister_recherchePuisArtisteDejaSuiviSansEspace_leSignaleCommeDejaSuivi() {
    final ArtisteUtilisateur suivi = mock(ArtisteUtilisateur.class);
    when(suivi.artisteAffiche()).thenReturn("Krashkarma");
    when(artisteRepository.listerPourUtilisateur("user1")).thenReturn(List.of(suivi));
    when(artisteRepository.rechercherEnBase(anyString())).thenReturn(List.of());
    final ArtisteService espion = Mockito.spy(service);
    doReturn(List.of()).when(espion).rechercherWikidata("Krash Karma");
    doReturn(List.of()).when(espion).rechercherDeezer("Krash Karma");

    assertTrue(espion.lister("user1", "Krash Karma").dejaLeSuivi());
  }

  @Test
  void parseDeezerResultats_garde_laPopulariteEtLaFicheLaPlusPopulaire() {
    final String json =
        """
        {"data":[
          {"id":392181181,"name":"Krash Karma","nb_fan":0},
          {"id":648729,"name":"Krashkarma","nb_fan":1385},
          {"id":1,"name":"Krashkarma","nb_fan":3}
        ]}
        """;

    final List<SuggestionArtiste> resultats =
        ArtisteServiceParsing.parseDeezerResultats(new ObjectMapper(), json);

    assertEquals(2, resultats.size());
    assertEquals("Krashkarma", resultats.get(0).nom());
    assertEquals(1385, resultats.get(0).fans());
    assertEquals("Krash Karma", resultats.get(1).nom());
    assertEquals(0, resultats.get(1).fans());
  }

  // Wikidata et Deezer partent en parallèle dès que la recherche en base ne suffit pas — un
  // enchaînement séquentiel (Wikidata puis Deezer seulement si toujours insuffisant) pouvait
  // approcher la somme des deux timeouts réseau dans le pire cas, constaté en E2E (webkit).
  @Test
  void lister_baseInsuffisante_appelleWikidataEtDeezer() {
    when(artisteRepository.listerPourUtilisateur("user1")).thenReturn(List.of());
    when(artisteRepository.rechercherEnBase(anyString())).thenReturn(List.of());

    final ArtisteService espion = Mockito.spy(service);
    doReturn(List.of(new SuggestionArtiste("Nina Simone", "wikidata", "chanteuse", "Q212484")))
        .when(espion)
        .rechercherWikidata("Nina Simone");
    doReturn(List.of()).when(espion).rechercherDeezer("Nina Simone");

    final var reponse = espion.lister("user1", "Nina Simone");

    assertEquals(1, reponse.suggestions().size());
    verify(espion).rechercherWikidata("Nina Simone");
    verify(espion).rechercherDeezer("Nina Simone");
  }

  // Avant le passage en parallèle, Deezer n'était interrogé que si Wikidata seul ne suffisait
  // pas (< 3 résultats après combinaison) — désormais les deux partent ensemble dès que la
  // recherche en base seule est insuffisante, Deezer est donc appelé même si Wikidata seul
  // atteint déjà le seuil de 3.
  @Test
  void lister_wikidataSeulAtteintLeSeuil_appelleQuandMemeDeezerEnParallele() {
    when(artisteRepository.listerPourUtilisateur("user1")).thenReturn(List.of());
    when(artisteRepository.rechercherEnBase(anyString())).thenReturn(List.of());

    final ArtisteService espion = Mockito.spy(service);
    doReturn(
            List.of(
                new SuggestionArtiste("A", "wikidata", "musicien", "Q1"),
                new SuggestionArtiste("B", "wikidata", "musicien", "Q2"),
                new SuggestionArtiste("C", "wikidata", "musicien", "Q3")))
        .when(espion)
        .rechercherWikidata("terme");
    doReturn(List.of()).when(espion).rechercherDeezer("terme");

    espion.lister("user1", "terme");

    verify(espion).rechercherDeezer("terme");
  }
}
