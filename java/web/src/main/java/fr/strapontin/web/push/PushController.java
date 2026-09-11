/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour les abonnements Web Push.
 *
 * <p>HTTP pur — délègue toute la logique à {@link PushService}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

  private final PushService pushService;
  private final NotificationPushService notificationPushService;

  /**
   * @param pushService service de gestion des abonnements push
   * @param notificationPushService service d'envoi de notifications push
   */
  public PushController(final PushService pushService, final NotificationPushService notificationPushService) {
    this.pushService = pushService;
    this.notificationPushService = notificationPushService;
  }

  /**
   * Retourne la clé VAPID publique (endpoint public).
   *
   * @return la clé publique ou {@code disponible: false} si non configurée
   */
  @GetMapping
  public ResponseEntity<ReponseVapid> obtenirCleVapid() {
    return ResponseEntity.ok(pushService.obtenirCleVapid());
  }

  /**
   * Enregistre un nouvel abonnement push.
   *
   * @param requete corps JSON avec endpoint et clés
   * @param auth contexte d'authentification
   * @return 201 si créé — 400/429 si invalide/cap (via {@link GlobalExceptionHandler})
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> abonner(
      @Valid @RequestBody final RequeteAbonnement requete, final Authentication auth) {
    pushService.abonner(((UtilisateurDetails) auth.getPrincipal()).id(), requete);
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  /**
   * Envoie une notification de test à l'abonnement de l'utilisateur courant.
   *
   * <p>Cible l'abonnement de l'appareil demandeur si {@code endpoint} est fourni, plutôt que de
   * sélectionner arbitrairement le plus récent.
   *
   * @param requete corps JSON optionnel avec {@code endpoint}
   * @param auth contexte d'authentification
   * @return 200 si envoyée, 400 si aucun abonnement actif
   */
  @PostMapping("/tester")
  public ResponseEntity<Map<String, Object>> tester(
      @Valid @RequestBody(required = false) final RequeteTesterPush requete, final Authentication auth) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    final String endpoint = requete != null ? requete.endpoint() : null;
    final boolean envoye = notificationPushService.envoyerNotificationTest(userId, endpoint);
    return envoye
        ? ResponseEntity.ok(Map.of("ok", true))
        : ResponseEntity.badRequest().body(Map.of("erreur", "Aucun abonnement actif."));
  }

  /**
   * Supprime un abonnement push.
   *
   * @param requete corps JSON avec l'endpoint à supprimer
   * @param auth contexte d'authentification
   * @return 200 si supprimé, 400 si endpoint manquant
   */
  @DeleteMapping
  public ResponseEntity<Map<String, Object>> desabonner(
      @Valid @RequestBody final RequeteDesabonnement requete, final Authentication auth) {
    pushService.desabonner(((UtilisateurDetails) auth.getPrincipal()).id(), requete.endpoint());
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /**
   * Liste les abonnements push de l'utilisateur courant, pour affichage dans /parametres.
   *
   * @param auth contexte d'authentification
   * @return les abonnements actifs
   */
  @GetMapping("/abonnements")
  public ResponseEntity<java.util.List<PushService.AbonnementDto>> listerAbonnements(
      final Authentication auth) {
    return ResponseEntity.ok(
        pushService.listerAbonnements(((UtilisateurDetails) auth.getPrincipal()).id()));
  }
}
