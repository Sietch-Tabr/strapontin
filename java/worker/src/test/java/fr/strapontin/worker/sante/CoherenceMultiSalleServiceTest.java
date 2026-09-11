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

/// Tests unitaires pour {@link CoherenceMultiSalleService}.
///
/// @since 2.8.8
/// @author Kwaite
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
      UUID salleId, String identifiant, String nomNorm, String signalee) {
    Map<String, Object> ligne = new HashMap<>();
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
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:les etoiles", "cafe de la danse", null)));

    int resultat = service.detecterContamination();

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
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneContamination(salleId, "internal:le zenith", "le zenith", null)));

    int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_signatureDejaConnue_neReSignalePas() {
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneContamination(
                    salleId, "internal:les etoiles", "cafe de la danse", "les etoiles")));

    int resultat = service.detecterContamination();

    assertEquals(0, resultat, "déjà signalée avec la même signature — pas de re-signalement");
    verify(jdbc, never()).update(anyString(), anyMap());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_nomComplenteDeVille_neJournalisePas() {
    // "6mic" / "6mic aix en provence" — l'identifiant synthétique n'est que le nom complété d'une
    // ville, pas une salle réellement différente.
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:6mic aix en provence", "6mic", null)));

    int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_sigleInitiales_neJournalisePas() {
    // "ed n" / "espace dollfus noack" — sigle formé des initiales de chaque mot du nom complet.
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(ligneContamination(salleId, "internal:ed n", "espace dollfus noack", null)));

    int resultat = service.detecterContamination();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterContamination_divergenceReelleMalgreLongueurAsymetrique_journalise() {
    // "theatre la boussole grande salle" / "theatre marigny grande salle" — ni nom+ville ni sigle,
    // divergence réelle malgré des longueurs de mots proches, ne doit pas être écartée du même
    // geste que les faux positifs nom+ville/sigle.
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneContamination(
                    salleId,
                    "internal:theatre la boussole grande salle",
                    "theatre marigny grande salle",
                    null)));

    int resultat = service.detecterContamination();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("warn"), anyString(), anyString(), anyMap());
  }

  private static Map<String, Object> ligneSalle(
      UUID id, String nomNorm, String dept, String doublonSignale) {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", id);
    ligne.put("nom_norm", nomNorm);
    ligne.put("dept", dept);
    ligne.put("doublon_signale", doublonSignale);
    return ligne;
  }

  @Test
  void detecterDoublons_pasDeSalles_retourneZero() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertEquals(0, service.detecterDoublons());
  }

  @Test
  void detecterDoublons_memeNomMemeDeptSansWikidataId_journalise() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", null),
                ligneSalle(b, "le kilowatt vitry sur seine", "94", null)));

    int resultat = service.detecterDoublons();

    assertEquals(1, resultat);
    verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("warn"), anyString(), anyString(), anyMap());
  }

  @Test
  void detecterDoublons_deptsDifferents_neJournalisePas() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", null),
                ligneSalle(b, "le kilowatt vitry sur seine", "75", null)));

    int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void detecterDoublons_pairejaSignalee_neReSignalePas() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "le kilowatt", "94", b.toString()),
                ligneSalle(b, "le kilowatt vitry sur seine", "94", null)));

    int resultat = service.detecterDoublons();

    assertEquals(0, resultat, "paire déjà signalée — pas de re-signalement");
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  // #1203 : le containment doit porter sur des tokens complets, pas sur une sous-chaîne brute —
  // "kowa" est une sous-chaîne de "kowalski" sans être le même mot.
  @Test
  void detecterDoublons_sousChaineBruteSansTokenComplet_neJournalisePas() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "kowa", "75", null),
                ligneSalle(b, "salle kowalski paris", "75", null)));

    int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
  }

  @Test
  void detecterDoublons_nomsSansRapport_neJournalisePas() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligneSalle(a, "zenith de paris", "75", null),
                ligneSalle(b, "olympia", "75", null)));

    int resultat = service.detecterDoublons();

    assertEquals(0, resultat);
  }
}
