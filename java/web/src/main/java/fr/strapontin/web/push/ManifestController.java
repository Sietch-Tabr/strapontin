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
 * Contrôleur servant le manifeste PWA dynamiquement.
 *
 * <p>{@code start_url} est dérivé de la requête HTTP entrante — fonctionne
 * sans configuration {@code PUBLIC_BASE_URL}, en dev comme en prod.
 *
 * <p>{@code id} est en revanche dérivé de {@code PUBLIC_BASE_URL} quand cette clé est
 * configurée, pour rester stable quel que soit l'hôte utilisé pour accéder à l'instance
 * (IP LAN, domaine public…) — un {@code id} variable ferait traiter chaque hôte comme une
 * installation PWA distincte plutôt qu'une mise à jour de la même application. Repli
 * sur le comportement dynamique historique si {@code PUBLIC_BASE_URL} n'est pas configurée.
 *
 * <p>Le manifeste est servi sans cache navigateur.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
public class ManifestController {

  private final ConfigService configService;

  /**
   * @param configService lecture de {@code PUBLIC_BASE_URL} pour stabiliser {@code id}
   */
  public ManifestController(final ConfigService configService) {
    this.configService = configService;
  }

  /**
   * Retourne le manifeste PWA au format {@code application/manifest+json}.
   *
   * @param request la requête HTTP courante (pour dériver la base URL)
   * @return le manifeste JSON avec {@code start_url} dynamique et {@code id} stable
   */
  @GetMapping(value = "/manifest.json", produces = "application/manifest+json")
  public ResponseEntity<Map<String, Object>> manifest(final HttpServletRequest request) {
    final String baseUrl = baseUrlDepuisRequete(request);
    final String idBaseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse(baseUrl);

    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("name", "Strapontin");
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
    manifest.put(
        "shortcuts",
        List.of(
            Map.of(
                "name", "Mes spectacles",
                "url", "/spectacles",
                "icons", List.of(Map.of("src", "/images/icon-192.png", "sizes", "192x192"))),
            Map.of(
                "name", "Découverte",
                "url", "/decouverte",
                "icons", List.of(Map.of("src", "/images/icon-192.png", "sizes", "192x192"))),
            Map.of(
                "name", "Nouveaux concerts",
                "url", "/?nouveau=true",
                "icons", List.of(Map.of("src", "/images/icon-192.png", "sizes", "192x192")))));

    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(manifest);
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
