/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link PreferencesController}.
///
/// @since 2.0.4
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class PreferencesControllerTest {

  @Mock private NamedParameterJdbcTemplate jdbc;

  private PreferencesController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new PreferencesController(jdbc, new ObjectMapper());
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "Kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void importer_contentTypeInvalide_retourne400() throws Exception {
    MockMultipartFile fichier =
        new MockMultipartFile("fichier", "prefs.txt", "text/plain", "{}".getBytes());

    ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(rep.getBody().containsKey("erreur"));
  }

  @Test
  void importer_versionInconnue_retourne422() throws Exception {
    String json =
        """
        {"version":2,"exporteAt":"2026-01-01T00:00:00Z",
         "artistes":[],"salles":[],"config":null}
        """;
    MockMultipartFile fichier =
        new MockMultipartFile(
            "fichier", "prefs.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertTrue(rep.getBody().containsKey("erreur"));
  }

  @Test
  void importer_artistesValides_retourneStats() throws Exception {
    String json =
        """
        {"version":1,"exporteAt":"2026-01-01T00:00:00Z",
         "artistes":[
           {"nom":"Daft Punk","normalise":"daft punk"},
           {"nom":"Radiohead","normalise":"radiohead"}
         ],"salles":[],"config":null}
        """;
    MockMultipartFile fichier =
        new MockMultipartFile(
            "fichier", "prefs.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    ResponseEntity<Map<String, Object>> rep =
        controller.importer(fichier, "fusionner", false, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertNotNull(rep.getBody());
    assertEquals(true, rep.getBody().get("importe"));
    assertEquals(2, rep.getBody().get("artistes"));
    assertEquals(0, rep.getBody().get("salles"));
    assertEquals(0, rep.getBody().get("rejetes"));
  }
}
