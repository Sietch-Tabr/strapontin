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
 * Tests unitaires pour {@link ReferentielArtistesController}.
 *
 * @since 2.6.0
 * @author Kwaite
 */
class ReferentielArtistesControllerTest {

  private ReferentielArtistesService service;
  private ReferentielArtistesController controller;

  @BeforeEach
  void setUp() {
    service = mock(ReferentielArtistesService.class);
    controller = new ReferentielArtistesController(service);
  }

  @Test
  void lister_delegueAuServiceAvecLesParametres() {
    when(service.lister(0, "Jain", null, false))
        .thenReturn(new ReponseReferentielArtistes(List.of(), 0, 1, 0));

    final ResponseEntity<ReponseReferentielArtistes> reponse = controller.lister(0, "Jain", null, false);

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).lister(0, "Jain", null, false);
  }

  @Test
  void modifierNomAffiche_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche("pas-un-uuid", new RequeteNomAffiche(null));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).modifierNomAffiche(any(), any());
  }

  @Test
  void modifierNomAffiche_artisteInconnu_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.modifierNomAffiche(eq(id), any())).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche(id.toString(), new RequeteNomAffiche("Nouveau"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserEnrichissement_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).reinitialiserEnrichissement(any());
  }

  @Test
  void reinitialiserEnrichissement_artisteInconnu_retourne404() {
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
  void ignorerIntervention_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).ignorerIntervention(any());
  }

  @Test
  void ignorerIntervention_artisteInconnu_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.ignorerIntervention(id)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void ignorerIntervention_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.ignorerIntervention(id)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void corrigerIdentifiantTm_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm("pas-un-uuid", new RequeteIdentifiantTm("K8vZ917G1B7"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).corrigerIdentifiantTm(any(), any());
  }

  // identifiant vide/absent : voir RequeteIdentifiantTmValidationTest (@Valid ne s'exerce pas
  // sur un appel Java direct au contrôleur, seulement via Spring MVC).

  @Test
  void corrigerIdentifiantTm_artisteSansSourceTicketmaster_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.corrigerIdentifiantTm(eq(id), any())).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(id.toString(), new RequeteIdentifiantTm("K8vZ917G1B7"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void corrigerIdentifiantTm_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.corrigerIdentifiantTm(id, "K8vZ917G1B7")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(id.toString(), new RequeteIdentifiantTm("K8vZ917G1B7"));

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_uuidIdentiques_retourne400() {
    final String id = UUID.randomUUID().toString();

    final ResponseEntity<Map<String, Object>> reponse = controller.fusionner(id, id);

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).fusionner(any(), any());
  }

  @Test
  void fusionner_artisteInconnu_retourne404() {
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
}
