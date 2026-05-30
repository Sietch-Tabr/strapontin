/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour les abonnements Web Push.
///
/// <p>HTTP pur — délègue toute la logique à {@link PushService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/push")
public class PushController {

  private final PushService pushService;

  /// @param pushService service de gestion des abonnements push
  public PushController(PushService pushService) {
    this.pushService = pushService;
  }

  /// Retourne la clé VAPID publique (endpoint public).
  ///
  /// @return la clé publique ou {@code disponible: false} si non configurée
  @GetMapping
  public ResponseEntity<ReponseVapid> obtenirCleVapid() {
    return ResponseEntity.ok(pushService.obtenirCleVapid());
  }

  /// Enregistre un nouvel abonnement push.
  ///
  /// @param requete corps JSON avec endpoint et clés
  /// @param auth    contexte d'authentification
  /// @return 201 si créé — 400/429 si invalide/cap (via {@link GlobalExceptionHandler})
  @PostMapping
  public ResponseEntity<Map<String, Object>> abonner(
      @RequestBody RequeteAbonnement requete, Authentication auth) {
    pushService.abonner(((UtilisateurDetails) auth.getPrincipal()).id(), requete);
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  /// Supprime un abonnement push.
  ///
  /// @param requete corps JSON avec l'endpoint à supprimer
  /// @param auth    contexte d'authentification
  /// @return 200 si supprimé, 400 si endpoint manquant
  @DeleteMapping
  public ResponseEntity<Map<String, Object>> desabonner(
      @RequestBody RequeteDesabonnement requete, Authentication auth) {
    if (requete.endpoint() == null || requete.endpoint().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Endpoint manquant."));
    }
    pushService.desabonner(((UtilisateurDetails) auth.getPrincipal()).id(), requete.endpoint());
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
