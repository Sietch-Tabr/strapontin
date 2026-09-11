/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link ManifestController} ({@code id} stable via
 * {@code PUBLIC_BASE_URL}, indépendant de l'hôte de la requête entrante).
 *
 * @since 2.6.5
 * @author Kwaite
 */
class ManifestControllerTest {

  private static HttpServletRequest requetePour(String scheme, String host, int port) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getScheme()).thenReturn(scheme);
    when(request.getServerName()).thenReturn(host);
    when(request.getServerPort()).thenReturn(port);
    when(request.getContextPath()).thenReturn("");
    return request;
  }

  @Test
  void manifest_publicBaseUrlConfiguree_idStableIndependantDeLHote() {
    ConfigService configService = mock(ConfigService.class);
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.kwaite.fr"));
    ManifestController controller = new ManifestController(configService);

    ResponseEntity<Map<String, Object>> repLan =
        controller.manifest(requetePour("http", "192.168.20.78", 8080));
    ResponseEntity<Map<String, Object>> repDomaine =
        controller.manifest(requetePour("https", "strapontin.kwaite.fr", 443));

    assertThat(repLan.getBody().get("id")).isEqualTo("https://strapontin.kwaite.fr/");
    assertThat(repDomaine.getBody().get("id")).isEqualTo("https://strapontin.kwaite.fr/");
    // start_url reste dynamique, dérivé de chaque requête, contrairement à id.
    assertThat(repLan.getBody().get("start_url")).isEqualTo("http://192.168.20.78:8080/");
  }

  @Test
  void manifest_publicBaseUrlAbsente_idReplieSurLHoteDeLaRequete() {
    ConfigService configService = mock(ConfigService.class);
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.empty());
    ManifestController controller = new ManifestController(configService);

    ResponseEntity<Map<String, Object>> reponse =
        controller.manifest(requetePour("http", "192.168.20.78", 8080));

    assertThat(reponse.getBody().get("id")).isEqualTo("http://192.168.20.78:8080/");
  }
}
