/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour l'onglet admin « Sources ».
///
/// <p>HTTP pur — délègue toute la logique métier à {@link SourcesService}.
/// Accessible uniquement aux utilisateurs avec le rôle {@code ADMIN}.
///
/// @since 2.3.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin")
public class SourcesController {

  private final SourcesService sourcesService;
  private final WorkerProxyService workerProxyService;

  /// @param sourcesService    service de gestion des sources
  /// @param workerProxyService proxy HTTP vers le worker
  public SourcesController(SourcesService sourcesService, WorkerProxyService workerProxyService) {
    this.sourcesService = sourcesService;
    this.workerProxyService = workerProxyService;
  }

  /// Retourne les statuts agrégés de toutes les sources et la liste des salles.
  ///
  /// @return 200 avec {@code {sources: [...], salles: [...]}}
  @GetMapping("/sources")
  public ResponseEntity<Map<String, Object>> lireSources() {
    return ResponseEntity.ok(sourcesService.lireSources());
  }

  /// Met à jour le slug Digitick d'une salle.
  ///
  /// @param salleUuid UUID stable de la salle
  /// @param corps     corps JSON avec {@code slug} (vide pour supprimer)
  /// @return 200 si mis à jour, 400 si UUID invalide
  @PutMapping("/salles/{salleUuid}/digitick-slug")
  public ResponseEntity<Map<String, Object>> mettreAJourDigitickSlug(
      @PathVariable String salleUuid, @RequestBody Map<String, String> corps) {
    UUID uuid = parseUuid(salleUuid);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    sourcesService.mettreAJourDigitickSlug(uuid, corps.getOrDefault("slug", ""));
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Teste l'accessibilité d'un slug Digitick via un HEAD HTTP.
  ///
  /// @param salleUuid UUID de la salle (pour cohérence d'URL)
  /// @param corps     corps JSON avec {@code slug}
  /// @return 200 si accessible, 404 sinon
  @PostMapping("/salles/{salleUuid}/tester-digitick")
  public ResponseEntity<Map<String, Object>> testerDigitickSlug(
      @PathVariable String salleUuid, @RequestBody Map<String, String> corps) {
    boolean accessible = sourcesService.testerDigitickSlug(corps.getOrDefault("slug", ""));
    return accessible
        ? ResponseEntity.ok(Map.of("accessible", true))
        : ResponseEntity.status(404).body(Map.of("accessible", false));
  }

  /// Déclenche immédiatement un {@code FetchSalleJob} pour la salle donnée.
  ///
  /// @param salleUuid UUID de la salle
  /// @return 200 si planifié, 404 si aucun identifiant connu, 503 si worker inaccessible
  @PostMapping("/salles/{salleUuid}/fetch")
  public ResponseEntity<Map<String, Object>> fetchSalle(@PathVariable String salleUuid) {
    UUID uuid = parseUuid(salleUuid);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    String identifiants = sourcesService.obtenirIdentifiantsSalle(uuid);
    if (identifiants == null) {
      return erreur(404, "Aucun identifiant source connu pour cette salle.");
    }
    boolean ok = workerProxyService.relancerSalle(identifiants);
    return ok
        ? ResponseEntity.ok(Map.of("statut", "déclenché"))
        : ResponseEntity.status(503).body(Map.of("erreur", "Worker inaccessible."));
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
