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
 * Contrôleur REST pour l'onglet admin « Signalements » (vue unifiée
 * du matching) — HTTP pur, délègue à {@link
 * SignalementsAdminService}, accessible aux seuls {@code ADMIN}.
 * @since 2.9.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/signalements")
public class SignalementsAdminController {

  private static final String MESSAGE_UUID_INVALIDE = "UUID de salle invalide.";

  private final SignalementsAdminService service;

  /**
   * @param service service de gestion des signalements
   */
  public SignalementsAdminController(final SignalementsAdminService service) {
    this.service = service;
  }

  /**
   * Liste paginée les signalements de matching actifs.
   *
   * @param page numéro de page (0-indexé, défaut 0)
   * @return 200 avec la page de résultats
   */
  @GetMapping
  public ResponseEntity<ReponseSignalements> lister(
      @RequestParam(defaultValue = "0") final int page) {
    return ResponseEntity.ok(service.lister(page));
  }

  /**
   * Marque un signalement comme résolu manuellement.
   *
   * @param id identifiant numérique du signalement
   * @return 200 si résolu, 404 si signalement inconnu ou déjà résolu
   */
  @PostMapping("/{id}/resoudre")
  public ResponseEntity<Map<String, Object>> resoudre(@PathVariable final long id) {
    final boolean resolu = service.resoudre(id);
    if (!resolu) {
      return erreur(404, "Signalement inconnu ou déjà résolu.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Déclare faux positif la contamination signalée sur une salle.
   *
   * @param salleId UUID de la salle
   * @return 200 si levée, 400 si UUID invalide, 404 si aucun signalement actif
   */
  @PostMapping("/contamination/{salleId}/accepter")
  public ResponseEntity<Map<String, Object>> accepterContamination(
      @PathVariable final String salleId) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) {
      return erreur(400, MESSAGE_UUID_INVALIDE);
    }
    if (!service.accepterContamination(uuid)) {
      return erreur(404, "Aucune contamination signalée pour cette salle.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Déclare distinctes les deux salles d'un doublon potentiel signalé.
   *
   * @param salleId UUID de la salle portant le signalement
   * @return 200 si levé, 400 si UUID invalide, 404 si aucun signalement actif
   */
  @PostMapping("/doublon/{salleId}/distinctes")
  public ResponseEntity<Map<String, Object>> declarerSallesDistinctes(
      @PathVariable final String salleId) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) {
      return erreur(400, MESSAGE_UUID_INVALIDE);
    }
    if (!service.declarerSallesDistinctes(uuid)) {
      return erreur(404, "Aucun doublon signalé pour cette salle.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
