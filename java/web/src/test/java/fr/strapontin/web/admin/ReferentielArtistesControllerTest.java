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

/// Tests unitaires pour {@link ReferentielArtistesController}.
///
/// @since 2.6.0
/// @author Kwaite
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

    ResponseEntity<ReponseReferentielArtistes> reponse = controller.lister(0, "Jain", null, false);

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).lister(0, "Jain", null, false);
  }

  @Test
  void modifierNomAffiche_uuidInvalide_retourne400() {
    ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche("pas-un-uuid", Map.of());

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).modifierNomAffiche(any(), any());
  }

  @Test
  void modifierNomAffiche_artisteInconnu_retourne404() {
    UUID id = UUID.randomUUID();
    when(service.modifierNomAffiche(eq(id), any())).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse =
        controller.modifierNomAffiche(id.toString(), Map.of("nomAffiche", "Nouveau"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserEnrichissement_uuidInvalide_retourne400() {
    ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).reinitialiserEnrichissement(any());
  }

  @Test
  void reinitialiserEnrichissement_artisteInconnu_retourne404() {
    UUID id = UUID.randomUUID();
    when(service.reinitialiserEnrichissement(id)).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserEnrichissement_nominal_retourne200() {
    UUID id = UUID.randomUUID();
    when(service.reinitialiserEnrichissement(id)).thenReturn(true);

    ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserEnrichissement(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void ignorerIntervention_uuidInvalide_retourne400() {
    ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).ignorerIntervention(any());
  }

  @Test
  void ignorerIntervention_artisteInconnu_retourne404() {
    UUID id = UUID.randomUUID();
    when(service.ignorerIntervention(id)).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void ignorerIntervention_nominal_retourne200() {
    UUID id = UUID.randomUUID();
    when(service.ignorerIntervention(id)).thenReturn(true);

    ResponseEntity<Map<String, Object>> reponse = controller.ignorerIntervention(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void corrigerIdentifiantTm_uuidInvalide_retourne400() {
    ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(
            "pas-un-uuid", Map.of("identifiantExterne", "K8vZ917G1B7"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).corrigerIdentifiantTm(any(), any());
  }

  @Test
  void corrigerIdentifiantTm_identifiantVide_retourne400() {
    UUID id = UUID.randomUUID();

    ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(id.toString(), Map.of("identifiantExterne", "  "));

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).corrigerIdentifiantTm(any(), any());
  }

  @Test
  void corrigerIdentifiantTm_identifiantAbsent_retourne400() {
    UUID id = UUID.randomUUID();

    ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(id.toString(), Map.of());

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).corrigerIdentifiantTm(any(), any());
  }

  @Test
  void corrigerIdentifiantTm_artisteSansSourceTicketmaster_retourne404() {
    UUID id = UUID.randomUUID();
    when(service.corrigerIdentifiantTm(eq(id), any())).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(
            id.toString(), Map.of("identifiantExterne", "K8vZ917G1B7"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void corrigerIdentifiantTm_nominal_retourne200() {
    UUID id = UUID.randomUUID();
    when(service.corrigerIdentifiantTm(id, "K8vZ917G1B7")).thenReturn(true);

    ResponseEntity<Map<String, Object>> reponse =
        controller.corrigerIdentifiantTm(
            id.toString(), Map.of("identifiantExterne", "K8vZ917G1B7"));

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_uuidIdentiques_retourne400() {
    String id = UUID.randomUUID().toString();

    ResponseEntity<Map<String, Object>> reponse = controller.fusionner(id, id);

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).fusionner(any(), any());
  }

  @Test
  void fusionner_artisteInconnu_retourne404() {
    UUID source = UUID.randomUUID();
    UUID cible = UUID.randomUUID();
    when(service.fusionner(cible, source)).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse =
        controller.fusionner(source.toString(), cible.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void fusionner_nominal_retourne200() {
    UUID source = UUID.randomUUID();
    UUID cible = UUID.randomUUID();
    when(service.fusionner(cible, source)).thenReturn(true);

    ResponseEntity<Map<String, Object>> reponse =
        controller.fusionner(source.toString(), cible.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }
}
