/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.LogsAdminWorker;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Tests unitaires pour {@link CoherenceMultiSalleService}.
 *
 * @since 2.8.8
 * @author Kwaite
 */
class CoherenceMultiSalleServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogsAdminWorker logsAdminWorker;
  private CoherenceMultiSalleService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new CoherenceMultiSalleService(jdbc, logsAdminWorker);
  }

  private static Map<String, Object> ligneContamination(
      final UUID salleId, final String identifiant, final String nomNorm, final String signalee) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("salle_id", salleId);
    ligne.put("identifiant", identifiant);
    ligne.put("nom_norm", nomNorm);
    ligne.put("signalee", signalee);
    return ligne;
  }

  @Test
  void detecterContamination_aucuneSourceInterne_retourneZero() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertEquals(0, service.detecterContamination());
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void detecterContamination_nomInterneDivergent_journaliseEtMemorise() {
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:les etoiles", "cafe de la danse", null)));

    final int resultat = service.detecterContamination();

    assertEquals(1, resultat);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> "les etoiles".equals(params.get("signature"))));
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("warn"), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_nomInterneCoherent_neJournalisePas() {
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneContamination(salleId, "internal:le zenith", "le zenith", null)));

    final int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_signatureDejaConnue_neReSignalePas() {
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneContamination(
                    salleId, "internal:les etoiles", "cafe de la danse", "les etoiles")));

    final int resultat = service.detecterContamination();

    assertEquals(0, resultat, "déjà signalée avec la même signature — pas de re-signalement");
    verify(jdbc, never()).update(anyString(), anyMap());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_nomComplenteDeVille_neJournalisePas() {
    // "6mic" / "6mic aix en provence" — l'identifiant synthétique n'est que le nom complété d'une
    // ville, pas une salle réellement différente.
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:6mic aix en provence", "6mic", null)));

    final int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  // Cas réel documenté en production (#1246) : "L'Astrada" (nom_norm "l astrada") / identifiant
  // Eventim interne "lastrada marciac" — l'apostrophe élidée devient un espace côté nom_norm
  // ("l astrada", token "l" filtré par longueur) mais est absente côté source synthétique
  // ("lastrada"), aucun token commun sans ce fix.
  @Test
  void detecterContamination_apostropheElidee_neJournalisePas() {
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:lastrada marciac", "l astrada", null)));

    final int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_sigleInitiales_neJournalisePas() {
    // "ed n" / "espace dollfus noack" — sigle formé des initiales de chaque mot du nom complet.
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:ed n", "espace dollfus noack", null)));

    final int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_divergenceReelleMalgreLongueurAsymetrique_journalise() {
    // "theatre la boussole grande salle" / "theatre marigny grande salle" — divergence réelle,
    // ni nom+ville ni sigle, ne doit pas être écartée avec les faux positifs.
    final UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneContamination(
                    salleId,
                    "internal:theatre la boussole grande salle",
                    "theatre marigny grande salle",
                    null)));

    final int resultat = service.detecterContamination();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("warn"), anyString(), anyString(), anyMap());
  }

  private static Map<String, Object> ligneSalle(
      final UUID id, final String nomNorm, final String dept, final String doublonSignale) {
    return ligneSalle(id, nomNorm, dept, doublonSignale, null);
  }

  private static Map<String, Object> ligneSalle(
      final UUID id,
      final String nomNorm,
      final String dept,
      final String doublonSignale,
      final UUID salleParentId) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", id);
    ligne.put("nom_norm", nomNorm);
    ligne.put("dept", dept);
    ligne.put("doublon_signale", doublonSignale);
    ligne.put("salle_parent_id", salleParentId);
    return ligne;
  }

  @Test
  void detecterDoublons_pasDeSalles_retourneZero() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertEquals(0, service.detecterDoublons());
  }

  @Test
  void detecterDoublons_memeNomMemeDeptSansWikidataId_journalise() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", null),
                ligneSalle(b, "le kilowatt vitry sur seine", "94", null)));

    final int resultat = service.detecterDoublons();

    assertEquals(1, resultat);
    verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("warn"), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterDoublons_deptsDifferents_neJournalisePas() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", null),
                ligneSalle(b, "le kilowatt vitry sur seine", "75", null)));

    final int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void detecterDoublons_complexeEtSousSalleDejaRelies_neJournalisePas() {
    // Satisferait le containment de tokens par construction, mais salle_parent_id les relie déjà
    // comme complexe/sous-salle légitimes.
    final UUID complexe = UUID.randomUUID();
    final UUID sousSalle = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(complexe, "la seine musicale", "92", null),
                ligneSalle(sousSalle, "la seine musicale grande seine", "92", null, complexe)));

    final int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void detecterDoublons_pairejaSignalee_neReSignalePas() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", b.toString()),
                ligneSalle(b, "le kilowatt vitry sur seine", "94", null)));

    final int resultat = service.detecterDoublons();

    assertEquals(0, resultat, "paire déjà signalée — pas de re-signalement");
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  // #1203 : le containment doit porter sur des tokens complets, pas sur une sous-chaîne brute —
  // "kowa" est une sous-chaîne de "kowalski" sans être le même mot.
  @Test
  void detecterDoublons_sousChaineBruteSansTokenComplet_neJournalisePas() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "kowa", "75", null),
                ligneSalle(b, "salle kowalski paris", "75", null)));

    final int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
  }

  @Test
  void detecterDoublons_nomsSansRapport_neJournalisePas() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "zenith de paris", "75", null),
                ligneSalle(b, "olympia", "75", null)));

    final int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
  }
}
