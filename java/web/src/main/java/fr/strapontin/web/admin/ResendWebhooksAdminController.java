/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.email.ResendWebhooksAdminService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour la section admin « Webhooks Resend » — liste et
 * rejeu des événements en échec de livraison — HTTP pur, délègue à
 * {@link ResendWebhooksAdminService}, accessible aux seuls {@code ADMIN}.
 * @since 2.9.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/webhooks/resend")
public class ResendWebhooksAdminController {

  private final ResendWebhooksAdminService service;

  /**
   * @param service service de gestion des webhooks Resend
   */
  public ResendWebhooksAdminController(final ResendWebhooksAdminService service) {
    this.service = service;
  }

  /**
   * Liste les événements récents du webhook Resend configuré.
   *
   * @return 200 avec la liste des événements (vide si non configuré)
   */
  @GetMapping("/evenements")
  public ResponseEntity<List<ResendWebhooksAdminService.EvenementWebhook>> listerEvenements() {
    return ResponseEntity.ok(service.listerEvenements());
  }

  /**
   * Rejoue un événement webhook en échec.
   *
   * @param id identifiant de l'événement à rejouer
   * @return 200 si le rejeu a été accepté, 502 en cas d'échec (configuration
   *     absente, webhook introuvable, erreur API Resend)
   */
  @PostMapping("/evenements/{id}/rejouer")
  public ResponseEntity<Map<String, Object>> rejouerEvenement(@PathVariable final String id) {
    final boolean rejoue = service.rejouerEvenement(id);
    if (!rejoue) {
      return erreur(502, "Rejeu impossible — vérifier la configuration Resend.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Liste les tentatives de livraison d'un événement webhook.
   *
   * @param id identifiant de l'événement
   * @return 200 avec la liste des tentatives (vide si non configuré/introuvable)
   */
  @GetMapping("/evenements/{id}/tentatives")
  public ResponseEntity<List<ResendWebhooksAdminService.TentativeWebhook>> listerTentatives(
      @PathVariable final String id) {
    return ResponseEntity.ok(service.listerTentatives(id));
  }

  /**
   * Fait tourner le secret de signature du webhook configuré, sans repasser
   * par le dashboard Resend.
   *
   * @return 200 si renouvelé, 502 en cas d'échec (configuration absente,
   *     webhook introuvable, erreur API Resend)
   */
  @PostMapping("/rotation-secret")
  public ResponseEntity<Map<String, Object>> rotationSecret() {
    final Optional<String> nouveauSecret = service.rotationSecretWebhook();
    if (nouveauSecret.isEmpty()) {
      return erreur(502, "Rotation impossible — vérifier la configuration Resend.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Liste les adresses sur la liste de suppression Resend.
   *
   * @return 200 avec la liste des entrées (vide si non configuré)
   */
  @GetMapping("/suppressions")
  public ResponseEntity<List<ResendWebhooksAdminService.EntreeSuppression>> listerSuppressions() {
    return ResponseEntity.ok(service.listerSuppressions());
  }
}
