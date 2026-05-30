/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.HashMap;
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
  /// @return 201 si créé, 400/429 en cas d'erreur
  @PostMapping
  public ResponseEntity<Map<String, Object>> abonner(
      @RequestBody RequeteAbonnement requete, Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    return switch (pushService.abonner(userId, requete)) {
      case SUCCES -> ResponseEntity.status(201).body(Map.of("ok", true));
      case INCOMPLET -> erreur(400, "Abonnement incomplet.");
      case INVALIDE -> erreur(400, "Données d'abonnement invalides.");
      case CAP_ATTEINT -> erreur(429, "Trop d'abonnements push pour cet utilisateur.");
    };
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
      return erreur(400, "Endpoint manquant.");
    }
    pushService.desabonner(((UtilisateurDetails) auth.getPrincipal()).id(), requete.endpoint());
    return ResponseEntity.ok(Map.of("ok", true));
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
