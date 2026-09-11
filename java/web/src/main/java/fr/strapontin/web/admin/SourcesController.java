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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour l'onglet admin « Sources ».
 *
 * <p>HTTP pur — délègue toute la logique métier à {@link SourcesService}. Accessible uniquement
 * aux utilisateurs avec le rôle {@code ADMIN}.
 *
 * @since 2.3.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class SourcesController {

  private final SourcesService sourcesService;
  private final WorkerProxyService workerProxyService;

  /**
   * @param sourcesService service de gestion des sources
   * @param workerProxyService proxy HTTP vers le worker
   */
  public SourcesController(final SourcesService sourcesService, final WorkerProxyService workerProxyService) {
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
   * Liste paginée des salles pour l'onglet admin « Sources ».
   *
   * @param digitick {@code true} pour les salles avec un slug Digitick configuré, {@code false}
   *   (défaut) pour celles sans identifiant Ticketmaster ni Eventim
   * @param page numéro de page (0-indexé, défaut 0)
   * @param nom filtre nom partiel
   * @return 200 avec la page de résultats
   */
  @GetMapping("/sources/salles")
  public ResponseEntity<ReponseSallesSources> listerSalles(
      @RequestParam(defaultValue = "false") final boolean digitick,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(required = false) final String nom) {
    return ResponseEntity.ok(sourcesService.listerSalles(digitick, page, nom));
  }

  /**
   * Met à jour le slug Digitick d'une salle.
   *
   * @param salleId UUID stable de la salle
   * @param corps corps JSON avec {@code slug} (vide pour supprimer)
   * @return 200 si mis à jour, 400 si UUID invalide
   */
  @PutMapping("/salles/{salleId}/digitick-slug")
  public ResponseEntity<Map<String, Object>> mettreAJourDigitickSlug(
      @PathVariable final String salleId, @Valid @RequestBody final RequeteSlug corps) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    sourcesService.mettreAJourDigitickSlug(uuid, corps.slug() == null ? "" : corps.slug());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Teste un slug Digitick : vérifie l'accessibilité et compte les concerts disponibles.
   *
   * @param salleId UUID de la salle (pour cohérence d'URL)
   * @param corps corps JSON avec {@code slug}
   * @return 200 avec {@code {accessible: true, nbConcerts: N}}, 404 si inaccessible
   */
  @PostMapping("/salles/{salleId}/tester-digitick")
  public ResponseEntity<Map<String, Object>> testerDigitickSlug(
      @PathVariable final String salleId, @Valid @RequestBody final RequeteSlug corps) {
    final int nbConcerts = sourcesService.testerDigitickSlug(corps.slug() == null ? "" : corps.slug());
    return nbConcerts >= 0
        ? ResponseEntity.ok(Map.of("accessible", true, "nbConcerts", nbConcerts))
        : ResponseEntity.status(404).body(Map.of("accessible", false, "nbConcerts", 0));
  }

  /**
   * Déclenche immédiatement un {@code FetchSalleJob} pour la salle donnée.
   *
   * @param salleId UUID de la salle
   * @return 200 si planifié, 404 si aucun identifiant connu, 503 si worker inaccessible
   */
  @PostMapping("/salles/{salleId}/fetch")
  public ResponseEntity<Map<String, Object>> fetchSalle(@PathVariable final String salleId) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    final String identifiants = sourcesService.obtenirIdentifiantsSalle(uuid);
    if (identifiants == null) {
      return erreur(404, "Aucun identifiant source connu pour cette salle.");
    }
    final boolean ok = workerProxyService.relancerSalle(identifiants);
    return ok
        ? ResponseEntity.ok(Map.of("statut", "déclenché"))
        : ResponseEntity.status(503).body(Map.of("erreur", "Worker inaccessible."));
  }

  /**
   * Crée une salle Digitick dans le référentiel et déclenche un fetch immédiat.
   *
   * @param requete corps JSON avec {@code nom}, {@code slug}, {@code ville} (opt.), {@code dept}
   *   (opt.)
   * @return 201 avec {@code {salleId}} si créée, 400 si invalide
   */
  @PostMapping("/digitick-salles")
  public ResponseEntity<Map<String, Object>> creerSalleDigitick(
      @Valid @RequestBody final RequeteDigitickSalle requete) {
    final String nom = requete.nom() == null ? "" : requete.nom().strip();
    final String slug = requete.slug() == null ? "" : requete.slug().strip();
    if (nom.isEmpty() || nom.length() > 200) return erreur(400, "Nom de salle invalide.");
    if (slug.isEmpty() || slug.length() > 100) return erreur(400, "Slug Digitick invalide.");
    final String ville =
        requete.ville() == null || requete.ville().isBlank() ? null : requete.ville().strip();
    final String dept =
        requete.dept() == null || requete.dept().isBlank() ? null : requete.dept().strip();
    final UUID salleId = sourcesService.creerSalleDigitick(nom, slug, ville, dept);
    return ResponseEntity.status(201).body(Map.of("salleId", salleId.toString()));
  }

  /**
   * Met à jour les métadonnées d'une salle Digitick (nom, ville, département).
   *
   * @param salleId UUID de la salle
   * @param requete corps JSON avec les champs à modifier (null = inchangé)
   * @return 200 si mis à jour, 400 si UUID invalide
   */
  @PatchMapping("/digitick-salles/{salleId}")
  public ResponseEntity<Map<String, Object>> modifierSalleDigitick(
      @PathVariable final String salleId, @Valid @RequestBody final RequeteDigitickSalle requete) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    final String nom = requete.nom() == null || requete.nom().isBlank() ? null : requete.nom().strip();
    final String ville =
        requete.ville() == null || requete.ville().isBlank() ? null : requete.ville().strip();
    final String dept =
        requete.dept() == null || requete.dept().isBlank() ? null : requete.dept().strip();
    sourcesService.modifierSalleDigitick(uuid, nom, ville, dept);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Supprime une salle Digitick si elle n'est suivie par aucun utilisateur et sans concerts.
   *
   * @param salleId UUID de la salle
   * @return 204 si supprimée, 409 si des contraintes empêchent la suppression, 400 si UUID
   *   invalide
   */
  @DeleteMapping("/digitick-salles/{salleId}")
  public ResponseEntity<?> supprimerSalleDigitick(@PathVariable final String salleId) {
    final UUID uuid = parseUuid(salleId);
    if (uuid == null) return erreur(400, "UUID de salle invalide.");
    final boolean supprimee = sourcesService.supprimerSalleDigitick(uuid);
    if (!supprimee) {
      return erreur(409, "Cette salle est suivie par des utilisateurs ou possède des concerts.");
    }
    return ResponseEntity.noContent().build();
  }
}
