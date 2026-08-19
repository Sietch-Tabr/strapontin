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

/// Contrôleur REST pour l'onglet admin « Artistes » du référentiel.
///
/// <p>HTTP pur — délègue toute la logique métier à {@link ReferentielArtistesService}. Accessible
/// uniquement aux utilisateurs avec le rôle {@code ADMIN} (cf. {@code SecurityConfiguration},
/// {@code /api/admin/**}).
///
/// @since 2.6.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin")
public class ReferentielArtistesController {

  private final ReferentielArtistesService service;

  public ReferentielArtistesController(ReferentielArtistesService service) {
    this.service = service;
  }

  /// Liste paginée du référentiel artistes.
  ///
  /// @param page numéro de page (0-indexé, défaut 0)
  /// @param nom filtre nom partiel
  /// @param statut filtre {@code a_jour}/{@code invalide} (statut binaire)
  /// @param wikidataManquant si {@code true}, ne retourne que les artistes sans {@code wikidata_id}
  /// @return 200 avec la page de résultats
  @GetMapping("/artistes")
  public ResponseEntity<ReponseReferentielArtistes> lister(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String nom,
      @RequestParam(required = false) String statut,
      @RequestParam(defaultValue = "false") boolean wikidataManquant) {
    return ResponseEntity.ok(service.lister(page, nom, statut, wikidataManquant));
  }

  /// Modifie le nom affiché d'un artiste.
  ///
  /// @param uuid UUID de l'artiste
  /// @param corps corps JSON avec {@code nomAffiche} (null/vide pour revenir à l'auto)
  /// @return 200 si mis à jour, 400 si UUID invalide, 404 si artiste inconnu
  @PatchMapping("/artistes/{uuid}")
  public ResponseEntity<Map<String, Object>> modifierNomAffiche(
      @PathVariable String uuid, @Valid @RequestBody RequeteNomAffiche corps) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID d'artiste invalide.");
    boolean maj = service.modifierNomAffiche(id, corps.nomAffiche());
    if (!maj) return erreur(404, "Artiste inconnu.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Réinitialise l'enrichissement automatique d'un artiste ({@code wikidata_id}, {@code
  /// deezer_id}, {@code nom_affiche}) — corrige une mauvaise correspondance automatique en le
  /// remettant candidat à une nouvelle découverte.
  ///
  /// @param uuid UUID de l'artiste
  /// @return 200 si réinitialisé, 400 si UUID invalide, 404 si artiste inconnu
  @PostMapping("/artistes/{uuid}/reinitialiser-enrichissement")
  public ResponseEntity<Map<String, Object>> reinitialiserEnrichissement(
      @PathVariable String uuid) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID d'artiste invalide.");
    boolean maj = service.reinitialiserEnrichissement(id);
    if (!maj) return erreur(404, "Artiste inconnu.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Efface {@code necessite_intervention_le} sans fusion — l'admin juge la divergence de nom TM
  /// acceptable.
  ///
  /// @param uuid UUID de l'artiste
  /// @return 200 si mis à jour, 400 si UUID invalide, 404 si artiste inconnu/sans intervention
  @PostMapping("/artistes/{uuid}/ignorer-intervention")
  public ResponseEntity<Map<String, Object>> ignorerIntervention(@PathVariable String uuid) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID d'artiste invalide.");
    boolean maj = service.ignorerIntervention(id);
    if (!maj) return erreur(404, "Artiste inconnu.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Corrige directement l'identifiant Ticketmaster d'un artiste — alternative à la fusion pour un
  /// simple mauvais lien.
  ///
  /// @param uuid UUID de l'artiste
  /// @param corps corps JSON avec {@code identifiantExterne} (non vide)
  /// @return 200 si mis à jour, 400 si UUID invalide ou identifiant vide, 404 si l'artiste n'a pas
  ///   de source Ticketmaster, 409 si l'identifiant est déjà attribué à un autre artiste
  @PatchMapping("/artistes/{uuid}/identifiant-tm")
  public ResponseEntity<Map<String, Object>> corrigerIdentifiantTm(
      @PathVariable String uuid, @Valid @RequestBody RequeteIdentifiantTm corps) {
    UUID id = parseUuid(uuid);
    if (id == null) return erreur(400, "UUID d'artiste invalide.");
    boolean maj = service.corrigerIdentifiantTm(id, corps.identifiantExterne());
    if (!maj) return erreur(404, "Artiste inconnu ou sans source Ticketmaster.");
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Fusionne deux artistes (la source est absorbée par la cible puis supprimée).
  ///
  /// @param uuid UUID de l'artiste source
  /// @param uuidCible UUID de l'artiste cible
  /// @return 200 si fusionné, 400 si UUID invalide ou identiques, 404 si un artiste est inconnu
  @PostMapping("/artistes/{uuid}/fusionner/{uuidCible}")
  public ResponseEntity<Map<String, Object>> fusionner(
      @PathVariable String uuid, @PathVariable String uuidCible) {
    UUID source = parseUuid(uuid);
    UUID cible = parseUuid(uuidCible);
    if (source == null || cible == null) return erreur(400, "UUID invalide.");
    if (source.equals(cible)) return erreur(400, "Les deux artistes doivent être différents.");
    boolean fusionne = service.fusionner(cible, source);
    if (!fusionne) return erreur(404, "Artiste source ou cible inconnu.");
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
