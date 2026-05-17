/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.config.ConfigService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur servant le manifeste PWA dynamiquement.
///
/// <p>Dynamique car {@code start_url} dépend de {@code PUBLIC_BASE_URL} dans {@code app_config}.
/// Le manifeste est mis en cache navigateur 1 heure.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class ManifestController {

  private static final String BASE_URL_DEFAUT = "https://strapontin.kwaite.fr";

  private final ConfigService configService;

  public ManifestController(ConfigService configService) {
    this.configService = configService;
  }

  /// Retourne le manifeste PWA au format {@code application/manifest+json}.
  ///
  /// @return le manifeste JSON avec {@code start_url} dynamique
  @GetMapping(value = "/manifest.json", produces = "application/manifest+json")
  public ResponseEntity<Map<String, Object>> manifest() {
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse(BASE_URL_DEFAUT);

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("name", "Strapontin");
    manifest.put("short_name", "Strapontin");
    manifest.put("description", "Trouvez les concerts de vos artistes préférés");
    manifest.put("id", baseUrl + "/");
    manifest.put("start_url", baseUrl + "/");
    manifest.put("scope", "/");
    manifest.put("display", "standalone");
    manifest.put("background_color", "#111827");
    manifest.put("theme_color", "#111827");
    manifest.put("lang", "fr");
    manifest.put(
        "icons",
        List.of(
            Map.of(
                "src", "/images/icon-192.png",
                "sizes", "192x192",
                "type", "image/png",
                "purpose", "any maskable"),
            Map.of(
                "src", "/images/icon-512.png",
                "sizes", "512x512",
                "type", "image/png",
                "purpose", "any maskable")));

    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(manifest);
  }
}
