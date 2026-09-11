/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.email.ResendWebhooksAdminService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour la section admin « Webhooks Resend » — liste et rejeu des événements en
 * échec de livraison, sans exposer directement l'API Resend au frontend.
 *
 * <p>HTTP pur — délègue toute la logique métier à {@link ResendWebhooksAdminService}. Accessible
 * uniquement aux utilisateurs avec le rôle {@code ADMIN} (cf. {@code SecurityConfiguration},
 * {@code /api/admin/**}).
 *
 * @since 2.9.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/webhooks/resend")
public class ResendWebhooksAdminController {

  private final ResendWebhooksAdminService service;

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
   * @return 200 si le rejeu a été accepté, 502 en cas d'échec (configuration absente, webhook
   *   introuvable, erreur API Resend)
   */
  @PostMapping("/evenements/{id}/rejouer")
  public ResponseEntity<Map<String, Object>> rejouerEvenement(@PathVariable final String id) {
    final boolean rejoue = service.rejouerEvenement(id);
    if (!rejoue) return erreur(502, "Rejeu impossible — vérifier la configuration Resend.");
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
