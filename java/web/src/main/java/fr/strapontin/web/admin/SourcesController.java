/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;
import static fr.strapontin.web.ControllerUtils.parseUuid;

import jakarta.validation.Valid;
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

/**
 * Contrôleur REST pour l'onglet admin « Sources » — HTTP pur, délègue à
 * {@link SourcesService}, accessible aux seuls utilisateurs {@code ADMIN}.
 * @since 2.3.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class SourcesController {

  private static final String MESSAGE_UUID_SALLE_INVALIDE = "UUID de salle invalide.";

  private final SourcesService sourcesService;
  private final WorkerProxyService workerProxyService;

  /**
   * @param sourcesService service de gestion des sources
   * @param workerProxyService proxy HTTP vers le worker
   */
  public SourcesController(
      final SourcesService sourcesService, final WorkerProxyService workerProxyService) {
    this.sourcesService = sourcesService;
    this.workerProxyService = workerProxyService;
  }

  /**
   * Retourne les statuts agrégés de toutes les sources.
   *
   * @return 200 avec {@code {sources: [...]}}
   */
  @GetMapping("/sources")
  public ResponseEntity<Map<String, Object>> lireSources() {
    return ResponseEntity.ok(sourcesService.lireSources());
  }

  /**
   * Définit (ou retire, si vide) le lieu Eventim interrogé pour une salle.
   *
   * @param salleId UUID stable de la salle
   * @param corps corps JSON avec {@code libelle} (vide pour retirer)
   * @return 200 si mis à jour, 400 si UUID invalide, 409 si déjà rattaché
   */
  @PutMapping("/salles/{salleId}/lieu-eventim")
  public ResponseEntity<Map<String, Object>> mettreAJourLieuEventim(
      @PathVariable final String salleId, @Valid @RequestBody final RequeteLieuEventim corps) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) {
      return erreur(400, MESSAGE_UUID_SALLE_INVALIDE);
    }
    sourcesService.mettreAJourLieuEventim(uuid, corps.libelle());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Teste un lieu Eventim : nombre de concerts à venir sous ce nom exact.
   *
   * @param salleId UUID de la salle (pour cohérence d'URL)
   * @param corps corps JSON avec {@code libelle}
   * @return 200 avec {@code {accessible: true, nbConcerts: N}}, 404 si
   *     Eventim n'a pas répondu ou libellé vide
   */
  @PostMapping("/salles/{salleId}/tester-lieu-eventim")
  public ResponseEntity<Map<String, Object>> testerLieuEventim(
      @PathVariable final String salleId, @Valid @RequestBody final RequeteLieuEventim corps) {
    final int nbConcerts = sourcesService.testerLieuEventim(corps.libelle());
    return nbConcerts >= 0
        ? ResponseEntity.ok(Map.of("accessible", true, "nbConcerts", nbConcerts))
        : ResponseEntity.status(404).body(Map.of("accessible", false, "nbConcerts", 0));
  }

  /**
   * Déclenche immédiatement un {@code FetchSalleJob} pour la salle donnée.
   *
   * @param salleId UUID de la salle
   * @return 200 si planifié, 404 si aucun identifiant connu, 503 si worker
   *     inaccessible
   */
  @PostMapping("/salles/{salleId}/fetch")
  public ResponseEntity<Map<String, Object>> fetchSalle(@PathVariable final String salleId) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) {
      return erreur(400, MESSAGE_UUID_SALLE_INVALIDE);
    }
    final String identifiants = sourcesService.obtenirIdentifiantsSalle(uuid);
    if (identifiants == null) {
      return erreur(404, "Aucun identifiant source connu pour cette salle.");
    }
    final boolean ok = workerProxyService.relancerSalle(identifiants);
    return ok
        ? ResponseEntity.ok(Map.of("statut", "déclenché"))
        : ResponseEntity.status(503).body(Map.of("erreur", WorkerProxyService.MESSAGE_INJOIGNABLE));
  }
}
