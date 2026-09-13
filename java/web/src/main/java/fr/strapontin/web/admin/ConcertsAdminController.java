/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;
import static fr.strapontin.web.ControllerUtils.parseUuid;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour l'onglet admin « Concerts » (revue des concerts flagués pour suppression).
 *
 * <p>HTTP pur — délègue toute la logique métier à {@link ConcertsAdminService}. Accessible
 * uniquement aux utilisateurs avec le rôle {@code ADMIN} (cf. {@code SecurityConfiguration}, {@code
 * /api/admin/**}).
 *
 * @since 2.8.3
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/concerts-a-confirmer")
public class ConcertsAdminController {

  private final ConcertsAdminService service;

  /**
   * @param service service de revue des concerts flagués
   */
  public ConcertsAdminController(final ConcertsAdminService service) {
    this.service = service;
  }

  /**
   * Liste paginée les concerts flagués pour suppression, en attente de revue.
   *
   * @param page numéro de page (0-indexé, défaut 0)
   * @return 200 avec la page de résultats
   */
  @GetMapping
  public ResponseEntity<ReponseConcertsAConfirmer> lister(
      @RequestParam(defaultValue = "0") final int page) {
    return ResponseEntity.ok(service.lister(page));
  }

  /**
   * Supprime définitivement un concert flagué.
   *
   * @param uuid UUID du concert
   * @return 200 si supprimé, 400 si UUID invalide, 404 si concert inconnu ou
   *     non flagué
   */
  @PostMapping("/{uuid}/confirmer")
  public ResponseEntity<Map<String, Object>> confirmer(@PathVariable final String uuid) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, "UUID de concert invalide.");
    }
    final boolean supprime = service.confirmerSuppression(id);
    if (!supprime) {
      return erreur(404, "Concert inconnu ou non flagué.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Annule le flag de suppression d'un concert (faux positif).
   *
   * @param uuid UUID du concert
   * @return 200 si annulé, 400 si UUID invalide, 404 si concert inconnu ou non
   *     flagué
   */
  @PostMapping("/{uuid}/annuler")
  public ResponseEntity<Map<String, Object>> annuler(@PathVariable final String uuid) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, "UUID de concert invalide.");
    }
    final boolean annule = service.annulerSuppression(id);
    if (!annule) {
      return erreur(404, "Concert inconnu ou non flagué.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
