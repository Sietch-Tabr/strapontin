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
import org.mockito.junit.jupiter.MockitoExtension;

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

  @Test
  void supprimer_delegueAuRepository_sansException() {
    UUID artisteId = UUID.randomUUID();
    assertDoesNotThrow(() -> service.supprimer("u1", artisteId));
    verify(artisteRepository).supprimer("u1", artisteId);
  }
}
