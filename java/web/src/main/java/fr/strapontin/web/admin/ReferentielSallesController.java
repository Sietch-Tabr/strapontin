/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour l'onglet admin « Salles » du référentiel.
///
/// <p>HTTP pur — délègue toute la logique métier à {@link ReferentielSallesService}. Accessible
/// uniquement aux utilisateurs avec le rôle {@code ADMIN} (cf. {@code SecurityConfiguration},
/// {@code /api/admin/**}).
///
/// @since 2.6.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin")
public class ReferentielSallesController {

  private final ReferentielSallesService service;

  public ReferentielSallesController(ReferentielSallesService service) {
    this.service = service;
  }

  /// Liste paginée du référentiel salles.
  ///
  /// @param page numéro de page (0-indexé, défaut 0)
  /// @param nom filtre nom partiel
  /// @param ville filtre ville partielle
  /// @param dept filtre département exact
  /// @param statut filtre {@code a_jour}/{@code a_verifier}/{@code invalide}
  /// @param wikidataManquant si {@code true}, ne retourne que les salles sans {@code wikidata_id}
  /// @return 200 avec la page de résultats
  @GetMapping("/salles")
  public ResponseEntity<ReponseReferentielSalles> lister(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String nom,
      @RequestParam(required = false) String ville,
      @RequestParam(required = false) String dept,
      @RequestParam(required = false) String statut,
      @RequestParam(defaultValue = "false") boolean wikidataManquant) {
    return ResponseEntity.ok(service.lister(page, nom, ville, dept, statut, wikidataManquant));
  }

  /// Modifie le nom affiché d'une salle.
  ///
  /// @param uuid UUID de la salle
  /// @param corps corps JSON avec {@code nomAffiche} (null/vide pour revenir à l'auto)
  /// @return 200 si mis à jour, 400 si UUID invalide, 404 si salle inconnue
  @PatchMapping("/salles/{uuid}")
  public ResponseEntity<Map<String, Object>> modifierNomAffiche(
      @PathVariable String uuid, @Valid @RequestBody RequeteNomAffiche corps) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID de salle invalide.");
    boolean maj = service.modifierNomAffiche(id, corps.nomAffiche());
    if (!maj) return erreur(404, "Salle inconnue.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Réinitialise l'enrichissement automatique d'une salle ({@code wikidata_id}, {@code
  /// nom_affiche}, {@code ville}) — corrige une mauvaise correspondance automatique (ex. homonyme
  /// international) en la remettant candidate à une nouvelle découverte.
  ///
  /// @param uuid UUID de la salle
  /// @return 200 si réinitialisée, 400 si UUID invalide, 404 si salle inconnue
  @PostMapping("/salles/{uuid}/reinitialiser-enrichissement")
  public ResponseEntity<Map<String, Object>> reinitialiserEnrichissement(
      @PathVariable String uuid) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID de salle invalide.");
    boolean maj = service.reinitialiserEnrichissement(id);
    if (!maj) return erreur(404, "Salle inconnue.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Fusionne deux salles (la source est absorbée par la cible puis supprimée).
  ///
  /// @param uuid UUID de la salle source
  /// @param uuidCible UUID de la salle cible
  /// @return 200 si fusionnée, 400 si UUID invalide ou identiques, 404 si une salle est inconnue
  @PostMapping("/salles/{uuid}/fusionner/{uuidCible}")
  public ResponseEntity<Map<String, Object>> fusionner(
      @PathVariable String uuid, @PathVariable String uuidCible) {
    UUID source = parseUuid(uuid);
    UUID cible = parseUuid(uuidCible);
    if (source == null || cible == null) return erreur(400, "UUID invalide.");
    if (source.equals(cible)) return erreur(400, "Les deux salles doivent être différentes.");
    boolean fusionnee = service.fusionner(cible, source);
    if (!fusionnee) return erreur(404, "Salle source ou cible inconnue.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  private static UUID parseUuid(String valeur) {
    try {
      return UUID.fromString(valeur);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
