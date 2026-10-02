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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour l'onglet admin « Salles » du référentiel — HTTP
 * pur, délègue à {@link ReferentielSallesService}, accessible aux
 * seuls {@code ADMIN} (cf. {@code SecurityConfiguration}).
 * @since 2.6.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class ReferentielSallesController {

  private final ReferentielSallesService service;
  private final RepartitionSalleService repartitionService;

  /**
   * @param service service de gestion du référentiel salles
   * @param repartitionService répartition d'une salle mélangeant plusieurs lieux
   */
  public ReferentielSallesController(
      final ReferentielSallesService service, final RepartitionSalleService repartitionService) {
    this.service = service;
    this.repartitionService = repartitionService;
  }

  /**
   * Libellés source des événements d'une salle, pour préparer sa répartition.
   *
   * @param uuid UUID de la salle
   * @return 200 avec les libellés et leur nombre d'événements, 400 si UUID invalide
   */
  @GetMapping("/salles/{uuid}/libelles-sources")
  public ResponseEntity<?> listerLibellesSources(@PathVariable final String uuid) {
    final UUID salleId = parseUuid(uuid);
    if (salleId == null) {
      return erreur(400, "UUID invalide.");
    }
    return ResponseEntity.ok(repartitionService.listerLibelles(salleId));
  }

  /**
   * Répartit une salle : les événements publiés sous les libellés choisis passent à une salle
   * existante ou créée.
   *
   * @param uuid UUID de la salle à répartir
   * @param requete libellés et destination
   * @return 200 avec le bilan, 400 si invalide, 404 si salle inconnue
   */
  @PostMapping("/salles/{uuid}/repartir")
  public ResponseEntity<?> repartir(
      @PathVariable final String uuid, @Valid @RequestBody final RequeteRepartitionSalle requete) {
    final UUID salleId = parseUuid(uuid);
    if (salleId == null) {
      return erreur(400, "UUID invalide.");
    }
    return ResponseEntity.ok(repartitionService.repartir(salleId, requete));
  }

  /**
   * Liste paginée du référentiel salles — mêmes filtres que {@link
   * ReferentielSallesService.FiltresRechercheSalles}.
   * @return 200 avec la page de résultats
   */
  @GetMapping("/salles")
  public ResponseEntity<ReponseReferentielSalles> lister(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(required = false) final String nom,
      @RequestParam(required = false) final String ville,
      @RequestParam(required = false) final String dept,
      @RequestParam(required = false) final String statut,
      @RequestParam(defaultValue = "false") final boolean wikidataManquant) {
    return ResponseEntity.ok(
        service.lister(
            new ReferentielSallesService.FiltresRechercheSalles(
                page, nom, ville, dept, statut, wikidataManquant)));
  }

  /**
   * Modifie le nom affiché d'une salle.
   *
   * @param uuid UUID de la salle
   * @param corps corps JSON avec {@code nomAffiche} (null/vide pour revenir à
   *     l'auto)
   * @return 200 si mis à jour, 400 si UUID invalide, 404 si salle inconnue
   */
  @PatchMapping("/salles/{uuid}")
  public ResponseEntity<Map<String, Object>> modifierNomAffiche(
      @PathVariable final String uuid, @Valid @RequestBody final RequeteNomAffiche corps) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, "UUID de salle invalide.");
    }
    final boolean maj = service.modifierNomAffiche(id, corps.nomAffiche());
    if (!maj) {
      return erreur(404, "Salle inconnue.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Réinitialise l'enrichissement auto d'une salle ({@code wikidata_id}/
   * {@code nom_affiche}/{@code ville}), la remet candidate à une
   * nouvelle découverte (corrige un homonyme international).
   * @param uuid UUID de la salle
   * @return 200 si réinitialisée, 400 si UUID invalide, 404 si inconnue
   */
  @PostMapping("/salles/{uuid}/reinitialiser-enrichissement")
  public ResponseEntity<Map<String, Object>> reinitialiserEnrichissement(
      @PathVariable final String uuid) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, "UUID de salle invalide.");
    }
    final boolean maj = service.reinitialiserEnrichissement(id);
    if (!maj) {
      return erreur(404, "Salle inconnue.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Fusionne deux salles (la source est absorbée par la cible puis supprimée).
   *
   * @param uuid UUID de la salle source
   * @param uuidCible UUID de la salle cible
   * @return 200 si fusionnée, 400 si UUID invalide ou identiques, 404 si une
   *     salle est inconnue
   */
  @PostMapping("/salles/{uuid}/fusionner/{uuidCible}")
  public ResponseEntity<Map<String, Object>> fusionner(
      @PathVariable final String uuid, @PathVariable final String uuidCible) {
    final UUID source = parseUuid(uuid);
    final UUID cible = parseUuid(uuidCible);
    if (source == null || cible == null) {
      return erreur(400, "UUID invalide.");
    }
    if (source.equals(cible)) {
      return erreur(400, "Les deux salles doivent être différentes.");
    }
    final boolean fusionnee = service.fusionner(cible, source);
    if (!fusionnee) {
      return erreur(404, "Salle source ou cible inconnue.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }
}
