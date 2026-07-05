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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link PreferencesService} — focus sur la dédup floue de l'import (#824).
///
/// @since 2.6.4
/// @author Kwaite
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
            jdbc, new ObjectMapper(), logAdminService, artisteService, salleService);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
  }

  // #824 : importer un artiste dont le nom est un doublon flou d'un artiste déjà suivi
  // ("The Rolling Stones" vs "Rolling Stones" déjà présent) doit réutiliser le même UUID via
  // ArtisteService.resoudreArtisteId, plutôt qu'un ON CONFLICT exact sur nom_norm qui créerait un
  // second artiste distinct.
  @Test
  void importerArtistes_doublonFlou_reutiliseUuidResoluParArtisteService() {
    UUID artisteExistant = UUID.randomUUID();
    when(artisteService.resoudreArtisteId(eq("The Rolling Stones"), anyString()))
        .thenReturn(artisteExistant);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    ExportComplet export =
        new ExportComplet(
            1, "2026-07-04", List.of(new ArtisteExport("The Rolling Stones", null)), null, null);

    PreferencesService.ResultatImportComplet resultat =
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

  // #824 : même principe côté salles — SalleService.resoudreSalleUuid plutôt qu'un ON CONFLICT
  // exact sur salle_cle.
  @Test
  void importerSalles_doublonFlou_reutiliseUuidResoluParSalleService() {
    UUID salleExistante = UUID.randomUUID();
    when(salleService.resoudreSalleUuid(eq("Zenith Paris"), anyString()))
        .thenReturn(salleExistante);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    ExportComplet export =
        new ExportComplet(
            1, "2026-07-04", null, List.of(new SalleExport(null, null, "Zenith Paris")), null);

    PreferencesService.ResultatImportComplet resultat =
        service.importer("u1", export, false, false);

    assertEquals(1, resultat.salles());
    verify(salleService).resoudreSalleUuid(eq("Zenith Paris"), anyString());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO salles"), anyMap());
  }
}
