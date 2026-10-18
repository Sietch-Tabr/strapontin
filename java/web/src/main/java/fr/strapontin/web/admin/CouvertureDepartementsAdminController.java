/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Départements sans concert à venir, pour l'onglet admin « Sources » — HTTP
 * pur, délègue à {@link CouvertureDepartementsAdminService}, accessible aux
 * seuls {@code ADMIN}.
 * @since 3.0.6
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/couverture-departements")
public class CouvertureDepartementsAdminController {

  private static final Pattern CODE_DEPARTEMENT = Pattern.compile("^[0-9A-B]{2,3}$");
  private static final String MESSAGE_CODE_INVALIDE = "Code de département invalide.";

  private final CouvertureDepartementsAdminService service;

  /**
   * @param service vue admin de la couverture des départements
   */
  public CouvertureDepartementsAdminController(final CouvertureDepartementsAdminService service) {
    this.service = service;
  }

  /**
   * @return 200 avec les départements sans concert à venir
   */
  @GetMapping
  public ResponseEntity<
          Map<String, List<CouvertureDepartementsAdminService.DepartementSansConcert>>>
      lister() {
    return ResponseEntity.ok(Map.of("departements", service.lister()));
  }

  /**
   * Déclare légitime l'absence de concert d'un département.
   *
   * @param dept code du département
   * @return 200 si enregistré, 400 si le code est invalide, 404 si le
   *     département a des concerts
   */
  @PostMapping("/{dept}/reconnaitre")
  public ResponseEntity<Map<String, Object>> reconnaitre(@PathVariable final String dept) {
    if (!CODE_DEPARTEMENT.matcher(dept).matches()) {
      return erreur(400, MESSAGE_CODE_INVALIDE);
    }
    if (!service.reconnaitre(dept)) {
      return erreur(404, "Ce département n'est pas sans concert.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Annule la reconnaissance de l'absence : le département sera de nouveau
   * signalé.
   *
   * @param dept code du département
   * @return 200 si levée, 400 si le code est invalide, 404 si aucune
   *     reconnaissance à lever
   */
  @PostMapping("/{dept}/rouvrir")
  public ResponseEntity<Map<String, Object>> rouvrir(@PathVariable final String dept) {
    if (!CODE_DEPARTEMENT.matcher(dept).matches()) {
      return erreur(400, MESSAGE_CODE_INVALIDE);
    }
    if (!service.rouvrir(dept)) {
      return erreur(404, "Aucune absence reconnue pour ce département.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
