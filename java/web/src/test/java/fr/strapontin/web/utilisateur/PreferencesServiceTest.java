/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.artiste.ArtisteService;
import fr.strapontin.web.salle.SalleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Tests unitaires pour {@link PreferencesService} — focus sur la dédup floue de l'import.
 *
 * @since 2.6.4
 * @author Kwaite
 */
class PreferencesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private ArtisteService artisteService;
  private SalleService salleService;
  private PreferencesService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    artisteService = mock(ArtisteService.class);
    salleService = mock(SalleService.class);
    service =
        new PreferencesService(
            jdbc,
            new ObjectMapper(),
            mock(RestClient.class),
            logAdminService,
            artisteService,
            salleService);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
  }

  // importer un artiste dont le nom est un doublon flou d'un artiste déjà suivi
  // ("The Rolling Stones" vs "Rolling Stones" déjà présent) doit réutiliser le même UUID via
  // ArtisteService.resoudreArtisteId, plutôt qu'un ON CONFLICT exact sur nom_norm qui créerait un
  // second artiste distinct.
  @Test
  void importerArtistes_doublonFlou_reutiliseUuidResoluParArtisteService() {
    final UUID artisteExistant = UUID.randomUUID();
    when(artisteService.resoudreArtisteId(eq("The Rolling Stones"), anyString()))
        .thenReturn(artisteExistant);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final ExportComplet export =
        new ExportComplet(
            1, "2026-07-04", List.of(new ArtisteExport("The Rolling Stones", null)), null, null);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(1, resultat.artistes());
    verify(artisteService).resoudreArtisteId(eq("The Rolling Stones"), anyString());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO user_artistes"),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, ?> params) ->
                    artisteExistant.toString().equals(params.get("artisteId"))));
    // Aucun INSERT direct dans `artistes` — la résolution passe entièrement par ArtisteService.
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO artistes"), anyMap());
  }

  // même principe côté salles — SalleService.resoudreSalleId plutôt qu'un ON CONFLICT
  // exact sur salle_cle.
  @Test
  void importerSalles_doublonFlou_reutiliseUuidResoluParSalleService() {
    final UUID salleExistante = UUID.randomUUID();
    when(salleService.resoudreSalleId(eq("Zenith Paris"), anyString())).thenReturn(salleExistante);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final ExportComplet export =
        new ExportComplet(
            1, "2026-07-04", null, List.of(new SalleExport(null, null, "Zenith Paris")), null);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(1, resultat.salles());
    verify(salleService).resoudreSalleId(eq("Zenith Paris"), anyString());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO salles"), anyMap());
  }

  @Test
  void importerArtistes_capAtteintPartiellement_tronqueEtRejetteLeSurplus() {
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("user_artistes"), anyMap(), eq(Integer.class)))
        .thenReturn(498);
    when(artisteService.resoudreArtisteId(anyString(), anyString())).thenReturn(UUID.randomUUID());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final ExportComplet export =
        new ExportComplet(
            1,
            "2026-07-04",
            List.of(
                new ArtisteExport("Artiste 1", null),
                new ArtisteExport("Artiste 2", null),
                new ArtisteExport("Artiste 3", null)),
            null,
            null);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(2, resultat.artistes(), "seuls 2 artistes doivent passer avant le cap de 500");
    assertEquals(1, resultat.rejetes());
  }

  @Test
  void importerSalles_capAtteintPartiellement_tronqueEtRejetteLeSurplus() {
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("user_salles"), anyMap(), eq(Integer.class)))
        .thenReturn(199);
    when(salleService.resoudreSalleId(anyString(), anyString())).thenReturn(UUID.randomUUID());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final ExportComplet export =
        new ExportComplet(
            1,
            "2026-07-04",
            null,
            List.of(new SalleExport(null, null, "Salle 1"), new SalleExport(null, null, "Salle 2")),
            null);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(1, resultat.salles(), "seule 1 salle doit passer avant le cap de 200");
    assertEquals(1, resultat.rejetes());
  }

  @Test
  void importerSalles_nomEtSalleCleVides_estRejetee() {
    final ExportComplet export =
        new ExportComplet(1, "2026-07-04", null, List.of(new SalleExport(null, null, "   ")), null);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(0, resultat.salles());
    assertEquals(1, resultat.rejetes());
    verify(salleService, never()).resoudreSalleId(anyString(), anyString());
  }

  @Test
  void importer_modeEcraser_supprimeAvantReimport() {
    when(jdbc.update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_artistes"), anyMap()))
        .thenReturn(3);
    when(jdbc.update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_salles"), anyMap()))
        .thenReturn(2);

    final ExportComplet export = new ExportComplet(1, "2026-07-04", null, null, null);

    service.importer("u1", export, true, false);

    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_artistes"), anyMap());
    verify(jdbc).update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_salles"), anyMap());
  }

  @Test
  void importer_ecraserFaux_neSupprimeRien() {
    final ExportComplet export = new ExportComplet(1, "2026-07-04", null, null, null);

    service.importer("u1", export, false, false);

    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_artistes"), anyMap());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("DELETE FROM user_salles"), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void importer_fusionDepartements_sansEcraserNiImporterConfig() {
    final ConfigExport config =
        new ConfigExport(List.of("75", "92"), true, "jamais", false, false, false, false, "auto");
    final ExportComplet export = new ExportComplet(1, "2026-07-04", null, null, config);

    service.importer("u1", export, false, false);

    final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("SELECT DISTINCT unnest"), captor.capture());
    assertEquals("u1", captor.getValue().get("userId"));
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO user_config"), anyMap());
  }

  @Test
  void importer_importerConfigVrai_remplaceToute_neFusionnePas() {
    final ConfigExport config =
        new ConfigExport(List.of("75"), true, "quotidien", true, false, false, false, "sombre");
    final ExportComplet export = new ExportComplet(1, "2026-07-04", null, null, config);

    service.importer("u1", export, false, true);

    verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO user_config"), anyMap());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("SELECT DISTINCT unnest"), anyMap());
  }

  @Test
  void exporterPuisImporter_roundTrip_conserveArtisteEtSalle() {
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("user_artistes"),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<ArtisteExport>>any()))
        .thenReturn(List.of(new ArtisteExport("Radiohead", "radiohead")));
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("user_salles"),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<SalleExport>>any()))
        .thenReturn(List.of(new SalleExport("zenithparis", null, "Zenith Paris")));
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("user_config"),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<ConfigExport>>any()))
        .thenReturn(List.of());

    final ExportComplet export = service.exporter("u1");

    when(artisteService.resoudreArtisteId(eq("Radiohead"), anyString()))
        .thenReturn(UUID.randomUUID());
    when(salleService.resoudreSalleId(eq("Zenith Paris"), anyString()))
        .thenReturn(UUID.randomUUID());
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final PreferencesService.ResultatImportComplet resultat =
        service.importer("u2", export, false, false);

    assertEquals(1, resultat.artistes());
    assertEquals(1, resultat.salles());
    assertEquals(0, resultat.rejetes());
  }
}
