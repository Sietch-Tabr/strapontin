/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.salle.SalleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires de {@link SourcesController}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class SourcesControllerTest {

  private SourcesService sourcesService;
  private WorkerProxyService workerProxyService;
  private SourcesController controller;

  @BeforeEach
  void setUp() {
    sourcesService = mock(SourcesService.class);
    workerProxyService = mock(WorkerProxyService.class);
    controller = new SourcesController(sourcesService, workerProxyService);
  }

  @Test
  void mettreAJourDigitickSlug_uuidInvalide_retourne400() {
    final var reponse =
        controller.mettreAJourDigitickSlug("pas-un-uuid", new RequeteSlug("slug-x"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(sourcesService, org.mockito.Mockito.never()).mettreAJourDigitickSlug(any(), any());
  }

  @Test
  void mettreAJourDigitickSlug_nominal_delegueAuServiceEtRetourne200() {
    final UUID id = UUID.randomUUID();

    final ResponseEntity<?> reponse =
        controller.mettreAJourDigitickSlug(id.toString(), new RequeteSlug("le-splendid"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(sourcesService).mettreAJourDigitickSlug(id, "le-splendid");
  }

  @Test
  void mettreAJourDigitickSlug_slugNull_transmetChaineVide() {
    final UUID id = UUID.randomUUID();

    controller.mettreAJourDigitickSlug(id.toString(), new RequeteSlug(null));

    verify(sourcesService).mettreAJourDigitickSlug(id, "");
  }

  @Test
  void testerDigitickSlug_accessible_retourne200AvecNbConcerts() {
    when(sourcesService.testerDigitickSlug("le-splendid")).thenReturn(5);

    final var reponse =
        controller.testerDigitickSlug("id-non-utilise", new RequeteSlug("le-splendid"));

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(5, reponse.getBody().get("nbConcerts"));
  }

  @Test
  void testerDigitickSlug_inaccessible_retourne404() {
    when(sourcesService.testerDigitickSlug("mauvais-slug")).thenReturn(-1);

    final var reponse =
        controller.testerDigitickSlug("id-non-utilise", new RequeteSlug("mauvais-slug"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void creerSalleDigitick_nomVide_retourne400() {
    final var reponse =
        controller.creerSalleDigitick(new RequeteDigitickSalle(" ", "slug-x", null, null));

    assertEquals(400, reponse.getStatusCode().value());
    verify(sourcesService, org.mockito.Mockito.never()).creerSalleDigitick(any());
  }

  @Test
  void creerSalleDigitick_slugVide_retourne400() {
    final var reponse =
        controller.creerSalleDigitick(new RequeteDigitickSalle("Salle X", " ", null, null));

    assertEquals(400, reponse.getStatusCode().value());
    verify(sourcesService, org.mockito.Mockito.never()).creerSalleDigitick(any());
  }

  @Test
  void creerSalleDigitick_nominal_retourne201AvecSalleId() {
    final UUID id = UUID.randomUUID();
    when(sourcesService.creerSalleDigitick(
            new SalleService.DonneesSalleDigitick("Salle X", "slug-x", "Nancy", "54")))
        .thenReturn(id);

    final var reponse =
        controller.creerSalleDigitick(new RequeteDigitickSalle("Salle X", "slug-x", "Nancy", "54"));

    assertEquals(201, reponse.getStatusCode().value());
    assertEquals(id.toString(), reponse.getBody().get("salleId"));
  }

  @Test
  void modifierSalleDigitick_uuidInvalide_retourne400() {
    final var reponse =
        controller.modifierSalleDigitick(
            "pas-un-uuid", new RequeteDigitickSalle("Salle X", null, null, null));

    assertEquals(400, reponse.getStatusCode().value());
    verify(sourcesService, org.mockito.Mockito.never())
        .modifierSalleDigitick(any(), any(), any(), any());
  }

  @Test
  void modifierSalleDigitick_nominal_delegueAuService() {
    final UUID id = UUID.randomUUID();

    final var reponse =
        controller.modifierSalleDigitick(
            id.toString(), new RequeteDigitickSalle("Nouveau nom", "  ", null, null));

    assertEquals(200, reponse.getStatusCode().value());
    verify(sourcesService).modifierSalleDigitick(id, "Nouveau nom", null, null);
  }

  @Test
  void listerSalles_delegueAuServiceAvecLesParametres() {
    when(sourcesService.listerSalles(true, 2, "olympia"))
        .thenReturn(new ReponseSallesSources(List.of(), 2, 3, 120));

    final var reponse = controller.listerSalles(true, 2, "olympia");

    assertEquals(200, reponse.getStatusCode().value());
    verify(sourcesService).listerSalles(true, 2, "olympia");
  }
}
