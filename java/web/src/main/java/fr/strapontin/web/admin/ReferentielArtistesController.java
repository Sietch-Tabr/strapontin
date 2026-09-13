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
 * Contrôleur REST pour l'onglet admin « Artistes » du référentiel — HTTP
 * pur, délègue à {@link ReferentielArtistesService}, accessible aux
 * seuls {@code ADMIN} (cf. {@code SecurityConfiguration}).
 * @since 2.6.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class ReferentielArtistesController {

  private static final String MESSAGE_UUID_ARTISTE_INVALIDE = "UUID d'artiste invalide.";
  private static final String CLE_SUCCES = "succes";

  private final ReferentielArtistesService service;

  /**
   * @param service service de gestion du référentiel artistes
   */
  public ReferentielArtistesController(final ReferentielArtistesService service) {
    this.service = service;
  }

  /**
   * Liste paginée du référentiel artistes.
   * @param page numéro de page (0-indexé, défaut 0)
   * @param nom filtre nom partiel
   * @param statut filtre a_jour/invalide (statut binaire)
   * @param wikidataManquant filtre les artistes sans wikidata_id
   * @return 200 avec la page de résultats
   */
  @GetMapping("/artistes")
  public ResponseEntity<ReponseReferentielArtistes> lister(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(required = false) final String nom,
      @RequestParam(required = false) final String statut,
      @RequestParam(defaultValue = "false") final boolean wikidataManquant) {
    return ResponseEntity.ok(service.lister(page, nom, statut, wikidataManquant));
  }

  /**
   * Modifie le nom affiché d'un artiste.
   *
   * @param uuid UUID de l'artiste
   * @param corps corps JSON avec {@code nomAffiche} (null/vide pour revenir à
   *     l'auto)
   * @return 200 si mis à jour, 400 si UUID invalide, 404 si artiste inconnu
   */
  @PatchMapping("/artistes/{uuid}")
  public ResponseEntity<Map<String, Object>> modifierNomAffiche(
      @PathVariable final String uuid, @Valid @RequestBody final RequeteNomAffiche corps) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, MESSAGE_UUID_ARTISTE_INVALIDE);
    }
    final boolean maj = service.modifierNomAffiche(id, corps.nomAffiche());
    if (!maj) {
      return erreur(404, "Artiste inconnu.");
    }
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /**
   * Réinitialise l'enrichissement auto d'un artiste ({@code wikidata_id}/
   * {@code deezer_id}/{@code nom_affiche}), le remet candidat à une
   * nouvelle découverte.
   * @param uuid UUID de l'artiste
   * @return 200 si réinitialisé, 400 si UUID invalide, 404 si inconnu
   */
  @PostMapping("/artistes/{uuid}/reinitialiser-enrichissement")
  public ResponseEntity<Map<String, Object>> reinitialiserEnrichissement(
      @PathVariable final String uuid) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, MESSAGE_UUID_ARTISTE_INVALIDE);
    }
    final boolean maj = service.reinitialiserEnrichissement(id);
    if (!maj) {
      return erreur(404, "Artiste inconnu.");
    }
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /**
   * Efface {@code necessite_intervention_le} sans fusion — l'admin juge la
   * divergence de nom TM acceptable.
   *
   * @param uuid UUID de l'artiste
   * @return 200 si mis à jour, 400 si UUID invalide, 404 si artiste
   *     inconnu/sans intervention
   */
  @PostMapping("/artistes/{uuid}/ignorer-intervention")
  public ResponseEntity<Map<String, Object>> ignorerIntervention(@PathVariable final String uuid) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, MESSAGE_UUID_ARTISTE_INVALIDE);
    }
    final boolean maj = service.ignorerIntervention(id);
    if (!maj) {
      return erreur(404, "Artiste inconnu.");
    }
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /**
   * Corrige directement l'identifiant Ticketmaster d'un artiste
   * (alternative à la fusion pour un lien erroné).
   * @param uuid UUID de l'artiste
   * @param corps corps JSON avec identifiantExterne (non vide)
   * @return 200 si mis à jour, 400 UUID/identifiant invalide, 404 sans
   *     source TM, 409 identifiant déjà attribué
   */
  @PatchMapping("/artistes/{uuid}/identifiant-tm")
  public ResponseEntity<Map<String, Object>> corrigerIdentifiantTm(
      @PathVariable final String uuid, @Valid @RequestBody final RequeteIdentifiantTm corps) {
    final UUID id = parseUuid(uuid);
    if (id == null) {
      return erreur(400, MESSAGE_UUID_ARTISTE_INVALIDE);
    }
    final boolean maj = service.corrigerIdentifiantTm(id, corps.identifiantExterne());
    if (!maj) {
      return erreur(404, "Artiste inconnu ou sans source Ticketmaster.");
    }
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /**
   * Fusionne deux artistes (la source est absorbée par la cible puis
   * supprimée).
   * @param uuid UUID de l'artiste source
   * @param uuidCible UUID de l'artiste cible
   * @return 200 si fusionné, 400 UUID invalide/identiques, 404 inconnu
   */
  @PostMapping("/artistes/{uuid}/fusionner/{uuidCible}")
  public ResponseEntity<Map<String, Object>> fusionner(
      @PathVariable final String uuid, @PathVariable final String uuidCible) {
    final UUID source = parseUuid(uuid);
    final UUID cible = parseUuid(uuidCible);
    if (source == null || cible == null) {
      return erreur(400, "UUID invalide.");
    }
    if (source.equals(cible)) {
      return erreur(400, "Les deux artistes doivent être différents.");
    }
    final boolean fusionne = service.fusionner(cible, source);
    if (!fusionne) {
      return erreur(404, "Artiste source ou cible inconnu.");
    }
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }
}
