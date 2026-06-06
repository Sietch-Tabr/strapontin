/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur servant le manifeste PWA dynamiquement.
///
/// <p>{@code start_url} est dérivé de la requête HTTP entrante — fonctionne
/// sans configuration {@code PUBLIC_BASE_URL}, en dev comme en prod.
/// Le manifeste est servi sans cache navigateur.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class ManifestController {

  /// Retourne le manifeste PWA au format {@code application/manifest+json}.
  ///
  /// @param request la requête HTTP courante (pour dériver la base URL)
  /// @return le manifeste JSON avec {@code start_url} dynamique
  @GetMapping(value = "/manifest.json", produces = "application/manifest+json")
  public ResponseEntity<Map<String, Object>> manifest(HttpServletRequest request) {
    String baseUrl = baseUrlDepuisRequete(request);

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("name", "Strapontin");
    manifest.put("short_name", "Strapontin");
    manifest.put("description", "Concert finder — suivez vos artistes et salles préférés");
    manifest.put("id", baseUrl + "/");
    manifest.put("start_url", baseUrl + "/");
    manifest.put("scope", "/");
    manifest.put("display", "standalone");
    manifest.put("background_color", "#ffffff");
    manifest.put("theme_color", "#ffffff");
    manifest.put("lang", "fr");
    manifest.put("categories", List.of("entertainment", "lifestyle"));
    manifest.put("launch_handler", Map.of("client_mode", "navigate-existing"));
    manifest.put(
        "icons",
        List.of(
            Map.of(
                "src", "/images/icon-192.png",
                "sizes", "192x192",
                "type", "image/png",
                "purpose", "any"),
            Map.of(
                "src", "/images/icon-512.png",
                "sizes", "512x512",
                "type", "image/png",
                "purpose", "any"),
            Map.of(
                "src", "/images/icon-512.png",
                "sizes", "512x512",
                "type", "image/png",
                "purpose", "maskable")));

    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(manifest);
  }

  private static String baseUrlDepuisRequete(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
      return scheme + "://" + host + request.getContextPath();
    }
    return scheme + "://" + host + ":" + port + request.getContextPath();
  }
}
