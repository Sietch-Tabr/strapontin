/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.admin.LogAdminService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/// Tests unitaires de {@link ArtisteService}.
///
/// <p>Couvre la logique métier de {@code ajouter()} : détection de doublon, cap 500, insertion.
///
/// @since 2.1.2
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class ArtisteServiceTest {

  @Mock private ArtisteRepository artisteRepository;
  @Mock private ObjectMapper objectMapper;
  @Mock private LogAdminService logAdminService;

  private ArtisteService service;

  @BeforeEach
  void setUp() {
    service = new ArtisteService(artisteRepository, objectMapper, logAdminService);
  }

  @Test
  void ajouter_artisteDejaPresent_leveRessourceDejaExistante() {
    UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(true);

    assertThrows(RessourceDejaExistante.class, () -> service.ajouter("u1", "Radiohead"));
    verify(artisteRepository, never()).ajouter(anyString(), anyString(), any());
  }

  @Test
  void ajouter_capAtteint_leveErreurMetier() {
    UUID artisteId = UUID.randomUUID();
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
    UUID artisteId = UUID.randomUUID();
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
    UUID artisteExistant = UUID.randomUUID();
    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", artisteExistant);
    candidat.put("nom_norm", "krash karma");
    when(artisteRepository.chercherCandidatsParTokens(any())).thenReturn(List.of(candidat));
    when(artisteRepository.existePourUtilisateur("u1", artisteExistant)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    service.ajouter("u1", "KrashKarma");

    verify(artisteRepository, never()).upsertArtiste(anyString(), anyString());
    verify(artisteRepository).ajouter("u1", "KrashKarma", artisteExistant);
  }

  @Test
  void ajouter_correspondanceExacteParmiLesCandidats_passeParUpsertPourMettreAJourLeNom() {
    UUID artisteId = UUID.randomUUID();
    Map<String, Object> candidat = new HashMap<>();
    candidat.put("id", artisteId);
    candidat.put("nom_norm", "radiohead");
    when(artisteRepository.chercherCandidatsParTokens(any())).thenReturn(List.of(candidat));
    when(artisteRepository.upsertArtiste("Radiohead", "radiohead")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    service.ajouter("u1", "Radiohead");

    verify(artisteRepository).upsertArtiste("Radiohead", "radiohead");
  }

  // #717 : sélection d'une suggestion Wikidata à l'ajout — résout le P2722 (Deezer ID) et
  // persiste les deux sources sans attendre le job nocturne. resoudreDeezerId est substituée
  // (Mockito.spy) pour éviter un appel réseau réel vers Wikidata en test unitaire.
  @Test
  void ajouter_wikidataIdFourni_resoutDeezerIdEtPersisteLesSources() {
    UUID artisteId = UUID.randomUUID();
    when(artisteRepository.upsertArtiste("Daft Punk", "daft punk")).thenReturn(artisteId);
    when(artisteRepository.existePourUtilisateur("u1", artisteId)).thenReturn(false);
    when(artisteRepository.compterPourUtilisateur("u1")).thenReturn(0);

    ArtisteService spy = Mockito.spy(service);
    doReturn("27").when(spy).resoudreDeezerId("Q167023");

    spy.ajouter("u1", "Daft Punk", "Q167023");

    verify(artisteRepository).enregistrerArtisteSource(artisteId, "wikidata", "Q167023");
    verify(artisteRepository).enregistrerArtisteSource(artisteId, "deezer", "27");
    verify(artisteRepository).mettreAJourIdsWikidata(artisteId, "Q167023", "27");
  }

  // #717 : conflit sur idx_artistes_wikidata_id (Q-ID déjà porté par un autre artiste) — rattrapé
  // et loggé, ne doit jamais faire échouer l'ajout à la liste de suivi.
  @Test
  void ajouter_wikidataIdDejaAttribueAUnAutreArtiste_neFaitPasEchouerLajout() {
    UUID artisteId = UUID.randomUUID();
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
    UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(1);

    assertDoesNotThrow(() -> service.supprimer("u1", artisteId));

    verify(artisteRepository).supprimer("u1", artisteId);
  }

  // #836 : un DELETE no-op (artiste déjà retiré, jamais suivi, ou appartenant à un autre
  // utilisateur) ne doit produire aucune entrée d'audit trompeuse "artiste supprimé".
  @Test
  void supprimer_artisteNonSuivi_neLogguePas() {
    UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(0);

    service.supprimer("u1", artisteId);

    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void supprimer_artisteReellementSupprime_logguePourAudit() {
    UUID artisteId = UUID.randomUUID();
    when(artisteRepository.supprimer("u1", artisteId)).thenReturn(1);

    service.supprimer("u1", artisteId);

    verify(logAdminService).logAdmin(eq("warn"), eq("web"), anyString(), anyMap());
  }

  // #780 : "Yael" doit être détecté comme correspondant partiellement à "yaelnaim" déjà suivi —
  // avant correctif, seule une égalité stricte était testée et l'utilisateur voyait "Aucun
  // résultat" alors que l'artiste était déjà suivi.
  @Test
  void contientCorrespondancePartielle_rechercheSubstringDunNomSuivi_retourneTrue() {
    assertTrue(
        ArtisteService.contientCorrespondancePartielle("yael", java.util.Set.of("yaelnaim")));
  }

  @Test
  void contientCorrespondancePartielle_correspondanceExacte_retourneTrue() {
    assertTrue(
        ArtisteService.contientCorrespondancePartielle("yaelnaim", java.util.Set.of("yaelnaim")));
  }

  @Test
  void contientCorrespondancePartielle_aucuneCorrespondance_retourneFalse() {
    assertFalse(
        ArtisteService.contientCorrespondancePartielle("radiohead", java.util.Set.of("yaelnaim")));
  }
}
