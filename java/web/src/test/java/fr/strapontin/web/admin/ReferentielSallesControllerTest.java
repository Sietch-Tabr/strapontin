/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link ReferentielSallesController}.
 *
 * @since 2.6.0
 * @author Kwaite
 */
class ReferentielSallesControllerTest {

  private ReferentielSallesService service;
  private ReferentielSallesController controller;

  @BeforeEach
  void setUp() {
    service = mock(ReferentielSallesService.class);
    controller = new ReferentielSallesController(service, mock(RepartitionSalleService.class));
  }

  @Test
  void lister_delegueAuServiceAvecLesParametres() {
    when(service.lister(
            new ReferentielSallesService.FiltresRechercheSalles(
                0, "Zenith", null, null, null, false)))
        .thenReturn(new ReponseReferentielSalles(List.of(), 0, 1, 0));

    final ResponseEntity<ReponseReferentielSalles> reponse =
        controller.lister(
            new CriteresListeSalles(0, "Zenith", null, null, null, false, null, false));

    assertEquals(200, reponse.getStatusCode().value());
    verify(service)
        .lister(
            new ReferentielSallesService.FiltresRechercheSalles(
                0, "Zenith", null, null, null, false));
  }

  @Test
  void lister_idsFournis_lesTransmetAuService() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final var filtres =
        new ReferentielSallesService.FiltresRechercheSalles(
            0, null, null, null, null, false, List.of(a, b));
    when(service.lister(filtres)).thenReturn(new ReponseReferentielSalles(List.of(), 0, 1, 0));

    final var reponse =
        controller.lister(
            new CriteresListeSalles(
                0, null, null, null, null, false, List.of(a.toString(), b.toString()), false));

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).lister(filtres);
  }

  @Test
  void lister_idInvalide_retourne400SansInterrogerLeService() {
    final var reponse =
        controller.lister(
            new CriteresListeSalles(
                0, null, null, null, null, false, List.of("pas-un-uuid"), false));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).lister(any());
  }

  @Test
  void modifierNomAffiche_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche("pas-un-uuid", new RequeteNomAffiche(null));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).modifierNomAffiche(any(), any());
  }

  @Test
  void modifierNomAffiche_salleInconnue_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.modifierNomAffiche(eq(id), any())).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche(id.toString(), new RequeteNomAffiche("Nouveau"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void modifierNomAffiche_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.modifierNomAffiche(eq(id), any())).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche(id.toString(), new RequeteNomAffiche("Nouveau"));

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserEnrichissement_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).reinitialiserEnrichissement(any());
  }

  @Test
  void reinitialiserEnrichissement_salleInconnue_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.reinitialiserEnrichissement(id)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserEnrichissement_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.reinitialiserEnrichissement(id)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.fusionner("pas-un-uuid", UUID.randomUUID().toString());

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_uuidIdentiques_retourne400() {
    final String id = UUID.randomUUID().toString();

    final ResponseEntity<Map<String, Object>> reponse = controller.fusionner(id, id);

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).fusionner(any(), any());
  }

  @Test
  void fusionner_salleInconnue_retourne404() {
    final UUID source = UUID.randomUUID();
    final UUID cible = UUID.randomUUID();
    when(service.fusionner(cible, source)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.fusionner(source.toString(), cible.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_nominal_retourne200() {
    final UUID source = UUID.randomUUID();
    final UUID cible = UUID.randomUUID();
    when(service.fusionner(cible, source)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.fusionner(source.toString(), cible.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  // La liaison des paramètres de requête au record (valeurs absentes, liste séparée par des
  // virgules) ne se voit pas en appelant la méthode directement.
  @Test
  void lister_parametresDeRequete_sontLiesAuxFiltres() throws Exception {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final var filtres =
        new ReferentielSallesService.FiltresRechercheSalles(
            2, "zenith", null, "75", null, true, List.of(a, b), true);
    when(service.lister(filtres)).thenReturn(new ReponseReferentielSalles(List.of(), 2, 3, 120));

    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
        .build()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/admin/salles")
                .param("page", "2")
                .param("nom", "zenith")
                .param("dept", "75")
                .param("wikidataManquant", "true")
                .param("ids", a + "," + b)
                .param("sansSource", "true"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    verify(service).lister(filtres);
  }

  @Test
  void lister_sansAucunParametre_appliqueLesDefauts() throws Exception {
    final var defauts =
        new ReferentielSallesService.FiltresRechercheSalles(
            0, null, null, null, null, false, List.of(), false);
    when(service.lister(defauts)).thenReturn(new ReponseReferentielSalles(List.of(), 0, 1, 0));

    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
        .build()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/admin/salles"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    verify(service).lister(defauts);
  }
}
