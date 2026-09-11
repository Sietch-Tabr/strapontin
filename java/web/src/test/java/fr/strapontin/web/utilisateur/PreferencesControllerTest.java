/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link PreferencesController}.
 *
 * @since 2.0.4
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class PreferencesControllerTest {

  @Mock private PreferencesService preferencesService;

  private PreferencesController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new PreferencesController(preferencesService);
    final UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void importer_contentTypeInvalide_retourne400() throws Exception {
    final MockMultipartFile fichier =
        new MockMultipartFile("fichier", "prefs.txt", "text/plain", "{}".getBytes());

    final ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(rep.getBody().containsKey("erreur"));
    verifyNoInteractions(preferencesService);
  }

  @Test
  void importer_versionInconnue_retourne422() throws Exception {
    final String json =
        """
            {"version":2,"exporteAt":"2026-01-01T00:00:00Z",
             "artistes":[],"salles":[],"config":null}
            """;
    final MockMultipartFile fichier =
        new MockMultipartFile(
            "fichier", "prefs.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    final ExportComplet exportVersion2 =
        new ExportComplet(2, "2026-01-01T00:00:00Z", List.of(), List.of(), null);
    when(preferencesService.deserialiserExport(any(byte[].class))).thenReturn(exportVersion2);

    final ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(rep.getBody().containsKey("erreur"));
  }

  @Test
  void importer_artistesValides_retourneStats() throws Exception {
    final String json =
        """
            {"version":1,"exporteAt":"2026-01-01T00:00:00Z",
             "artistes":[
               {"nom":"Daft Punk","normalise":"daft punk"},
               {"nom":"Radiohead","normalise":"radiohead"}
             ],"salles":[],"config":null}
            """;
    final MockMultipartFile fichier =
        new MockMultipartFile(
            "fichier", "prefs.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    final ExportComplet exportV1 =
        new ExportComplet(
            1,
            "2026-01-01T00:00:00Z",
            List.of(
                new ArtisteExport("Daft Punk", "daft punk"),
                new ArtisteExport("Radiohead", "radiohead")),
            List.of(),
            null);
    when(preferencesService.deserialiserExport(any(byte[].class))).thenReturn(exportV1);
    when(preferencesService.importer(eq("user-1"), any(ExportComplet.class), eq(false), eq(false)))
        .thenReturn(new PreferencesService.ResultatImportComplet(2, 0, 0));

    final ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertEquals(true, rep.getBody().get("importe"));
    assertEquals(2, rep.getBody().get("artistes"));
    assertEquals(0, rep.getBody().get("salles"));
    assertEquals(0, rep.getBody().get("rejetes"));
  }
}
