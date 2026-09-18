/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur servant le manifeste PWA dynamiquement, sans cache navigateur.
 * {@code start_url} dérive de la requête entrante ; {@code id} dérive de
 * {@code PUBLIC_BASE_URL} quand configurée, pour rester stable quel que
 * soit l'hôte d'accès (sinon chaque hôte serait une installation distincte).
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
public class ManifestController {

  private static final String CLE_NAME = "name";
  private static final String CLE_ICONS = "icons";
  private static final String CLE_SRC = "src";
  private static final String CLE_SIZES = "sizes";
  private static final String ICON_192 = "/images/icon-192.png";
  private static final String TAILLE_192 = "192x192";

  private final ConfigService configService;

  /**
   * @param configService lecture de {@code PUBLIC_BASE_URL} pour stabiliser
   *     {@code id}
   */
  public ManifestController(final ConfigService configService) {
    this.configService = configService;
  }

  /**
   * Retourne le manifeste PWA au format {@code application/manifest+json} .
   *
   * @param request la requête HTTP courante (pour dériver la base URL)
   * @return le manifeste JSON avec {@code start_url} dynamique et {@code id}
   *     stable
   */
  @GetMapping(value = "/manifest.json", produces = "application/manifest+json")
  public ResponseEntity<Map<String, Object>> manifest(final HttpServletRequest request) {
    final String baseUrl = baseUrlDepuisRequete(request);
    final String idBaseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse(baseUrl);

    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put(CLE_NAME, "Strapontin");
    manifest.put("short_name", "Strapontin");
    manifest.put("description", "Concert finder — suivez vos artistes et salles préférés");
    manifest.put("id", idBaseUrl + "/");
    manifest.put("start_url", baseUrl + "/");
    manifest.put("scope", "/");
    manifest.put("display", "standalone");
    manifest.put("background_color", "#ffffff");
    manifest.put("theme_color", "#ffffff");
    manifest.put("lang", "fr");
    manifest.put("categories", List.of("entertainment", "lifestyle"));
    manifest.put("launch_handler", Map.of("client_mode", "navigate-existing"));
    manifest.put(CLE_ICONS, construireIcones());
    manifest.put("shortcuts", construireRaccourcis());

    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(manifest);
  }

  private static List<Map<String, String>> construireIcones() {
    return List.of(
        Map.of(CLE_SRC, ICON_192, CLE_SIZES, TAILLE_192, "type", "image/png", "purpose", "any"),
        Map.of(
            CLE_SRC,
            "/images/icon-512.png",
            CLE_SIZES,
            "512x512",
            "type",
            "image/png",
            "purpose",
            "any"),
        Map.of(
            CLE_SRC,
            "/images/icon-512.png",
            CLE_SIZES,
            "512x512",
            "type",
            "image/png",
            "purpose",
            "maskable"));
  }

  private static List<Map<String, Object>> construireRaccourcis() {
    return List.of(
        Map.of(
            CLE_NAME,
            "Mes spectacles",
            "url",
            "/spectacles",
            CLE_ICONS,
            List.of(Map.of(CLE_SRC, ICON_192, CLE_SIZES, TAILLE_192))),
        Map.of(
            CLE_NAME,
            "Découverte",
            "url",
            "/decouverte",
            CLE_ICONS,
            List.of(Map.of(CLE_SRC, ICON_192, CLE_SIZES, TAILLE_192))),
        Map.of(
            CLE_NAME,
            "Nouveaux concerts",
            "url",
            "/?nouveau=true",
            CLE_ICONS,
            List.of(Map.of(CLE_SRC, ICON_192, CLE_SIZES, TAILLE_192))));
  }

  private static String baseUrlDepuisRequete(final HttpServletRequest request) {
    final String scheme = request.getScheme();
    final String host = request.getServerName();
    final int port = request.getServerPort();
    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
      return scheme + "://" + host + request.getContextPath();
    }
    return scheme + "://" + host + ":" + port + request.getContextPath();
  }
}
