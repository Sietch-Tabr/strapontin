/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la gestion des salles suivies (portage de {@code salles/+page.server.ts}).
///
/// <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link SalleService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/salles")
public class SalleController {

  private final SalleService salleService;

  /// @param salleService service de gestion des salles
  public SalleController(SalleService salleService) {
    this.salleService = salleService;
  }

  /// Liste les salles suivies, avec suggestions de recherche si {@code q} est fourni.
  ///
  /// @param q    terme de recherche (optionnel, min 2 caractères)
  /// @param auth contexte d'authentification
  /// @return la liste des salles et les suggestions
  @GetMapping
  public ResponseEntity<ReponseSalles> listerSalles(
      @RequestParam(required = false, defaultValue = "") String q, Authentication auth) {
    String userId = utilisateur(auth).id();
    return ResponseEntity.ok(salleService.lister(userId, q));
  }

  /// Ajoute une salle à la liste de suivi.
  ///
  /// @param requete corps JSON avec le nom et l'ID TM optionnel
  /// @param auth    contexte d'authentification
  /// @return 201 si créée — 409 si doublon, 400 si invalide/cap (via {@link GlobalExceptionHandler})
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterSalle(
      @RequestBody RequeteAjouter requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    if (nom.isEmpty() || nom.length() > 200) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Identifiant de salle invalide."));
    }
    String tmVenueId =
        requete.tmVenueId() == null || requete.tmVenueId().isBlank()
            ? null
            : requete.tmVenueId().strip();

    salleService.ajouter(userId, nom, tmVenueId);
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param salleId identifiant normalisé de la salle
  /// @param auth    contexte d'authentification
  /// @return 204 si supprimée
  @DeleteMapping("/{salleId}")
  public ResponseEntity<Void> supprimerSalle(@PathVariable String salleId, Authentication auth) {
    String userId = utilisateur(auth).id();
    salleService.supprimer(userId, salleId);
    return ResponseEntity.noContent().build();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
