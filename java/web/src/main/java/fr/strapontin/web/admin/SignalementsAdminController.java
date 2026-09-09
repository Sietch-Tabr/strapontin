/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour l'onglet admin « Signalements » (vue unifiée du matching, #1193).
///
/// <p>HTTP pur — délègue toute la logique métier à {@link SignalementsAdminService}. Accessible
/// uniquement aux utilisateurs avec le rôle {@code ADMIN} (cf. {@code SecurityConfiguration},
/// {@code /api/admin/**}).
///
/// @since 2.9.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin/signalements")
public class SignalementsAdminController {

  private final SignalementsAdminService service;

  public SignalementsAdminController(SignalementsAdminService service) {
    this.service = service;
  }

  /// Liste paginée les signalements de matching actifs.
  ///
  /// @param page numéro de page (0-indexé, défaut 0)
  /// @return 200 avec la page de résultats
  @GetMapping
  public ResponseEntity<ReponseSignalements> lister(@RequestParam(defaultValue = "0") int page) {
    return ResponseEntity.ok(service.lister(page));
  }

  /// Marque un signalement comme résolu manuellement.
  ///
  /// @param id identifiant numérique du signalement
  /// @return 200 si résolu, 404 si signalement inconnu ou déjà résolu
  @PostMapping("/{id}/resoudre")
  public ResponseEntity<Map<String, Object>> resoudre(@PathVariable long id) {
    boolean resolu = service.resoudre(id);
    if (!resolu) return erreur(404, "Signalement inconnu ou déjà résolu.");
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
