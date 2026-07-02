/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
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

/// Contrôleur REST pour la gestion des artistes suivis (portage de `artistes/+page.server.ts`).
///
/// <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link ArtisteService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/artistes")
public class ArtisteController {

  private final ArtisteService artisteService;

  /// @param artisteService service de gestion des artistes
  public ArtisteController(ArtisteService artisteService) {
    this.artisteService = artisteService;
  }

  /// Liste les artistes suivis, avec suggestions de recherche si {@code q} est fourni.
  ///
  /// @param q    terme de recherche (optionnel, min 2 caractères)
  /// @param auth contexte d'authentification Spring Security
  /// @return la liste des artistes et les suggestions
  @GetMapping
  public ResponseEntity<ReponseArtistes> listerArtistes(
      @RequestParam(required = false, defaultValue = "") String q, Authentication auth) {
    String userId = utilisateur(auth).id();
    return ResponseEntity.ok(artisteService.lister(userId, q));
  }

  /// Ajoute un artiste à la liste de suivi de l'utilisateur.
  ///
  /// @param requete corps JSON avec le nom de l'artiste
  /// @param auth    contexte d'authentification
  /// @return 201 si créé — 409 si doublon, 400 si cap atteint (via {@link GlobalExceptionHandler})
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterArtiste(
      @Valid @RequestBody RequeteAjouter requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    artisteService.ajouter(userId, requete.nom().strip(), requete.wikidataId());
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// @param artisteId UUID stable de l'artiste à supprimer
  /// @param auth      contexte d'authentification
  /// @return 204 si supprimé
  @DeleteMapping("/{artisteId}")
  public ResponseEntity<Void> supprimerArtiste(@PathVariable UUID artisteId, Authentication auth) {
    String userId = utilisateur(auth).id();
    artisteService.supprimer(userId, artisteId);
    return ResponseEntity.noContent().build();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }
}
