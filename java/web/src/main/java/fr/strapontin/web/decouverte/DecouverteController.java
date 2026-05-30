/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.decouverte;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la découverte de concerts par département.
///
/// <p>HTTP pur — délègue toute la logique à {@link DecouverteService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/decouverte")
public class DecouverteController {

  private final DecouverteService decouverteService;

  /// @param decouverteService service de découverte
  public DecouverteController(DecouverteService decouverteService) {
    this.decouverteService = decouverteService;
  }

  /// Retourne les concerts filtrés par département.
  ///
  /// @param auth contexte d'authentification
  /// @return les concerts et les filtres disponibles
  @GetMapping
  public ResponseEntity<ReponseDecouverte> decouvrir(
      @RequestParam(name = "dept", required = false) List<String> depts,
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "0") int page,
      Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    return ResponseEntity.ok(
        decouverteService.decouvrir(userId, depts, q, mois, genre, classification, tri, page));
  }

  /// Déclenche un fetch régional pour les départements fournis.
  ///
  /// @param requete corps JSON avec la liste de départements
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  @PostMapping("/fetcher")
  public ResponseEntity<Map<String, Object>> fetcher(@RequestBody RequeteFetch requete) {
    return decouverteService.fetcher();
  }
}
