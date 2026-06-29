/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour les spectacles (portage de {@code spectacles/+page.server.ts}).
///
/// <p>HTTP pur — délègue toute la logique à {@link SpectacleService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/spectacles")
public class SpectacleController {

  private final SpectacleService spectacleService;

  /// @param spectacleService service de gestion des spectacles
  public SpectacleController(SpectacleService spectacleService) {
    this.spectacleService = spectacleService;
  }

  /// Liste les spectacles selon les filtres fournis.
  ///
  /// @param auth contexte d'authentification
  /// @return la liste paginée des spectacles
  @GetMapping
  public ResponseEntity<ReponseSpectacles> listerSpectacles(
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "false") boolean quinzaine,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(name = "source", required = false) List<String> sources,
      @RequestParam(required = false) String indisponibles,
      @RequestParam(required = false, defaultValue = "0") int page,
      Authentication auth) {
    String userId = utilisateur(auth).id();
    FiltreConcerts filtre =
        new FiltreConcerts(
            q,
            mois,
            genre,
            classification,
            tri,
            page,
            quinzaine,
            masques,
            sources,
            indisponibles,
            null);
    return ResponseEntity.ok(spectacleService.listerSpectacles(userId, filtre));
  }

  /// Masque ou démasque un concert pour l'utilisateur courant.
  ///
  /// @param concertId identifiant UUID du concert
  /// @param requete   corps JSON avec {@code masquer}
  /// @param auth      contexte d'authentification
  /// @return 200 si mis à jour
  @PatchMapping("/matches/{concertId}")
  public ResponseEntity<Map<String, Object>> modifierMatch(
      @PathVariable String concertId, @RequestBody RequeteMasque requete, Authentication auth) {
    spectacleService.modifierMatch(utilisateur(auth).id(), concertId, requete.masquer());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Masque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code artisteNorm}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si artisteNorm manquant
  @PostMapping("/masquer-artiste")
  public ResponseEntity<Map<String, Object>> masquerArtiste(
      @RequestBody RequeteMasquerArtiste requete, Authentication auth) {
    if (requete.artisteNorm() == null || requete.artisteNorm().isBlank()) {
      return erreur(400, "Identifiant artiste manquant.");
    }
    spectacleService.masquerArtiste(utilisateur(auth).id(), requete.artisteNorm());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Démasque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code artisteNorm}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si artisteNorm manquant
  @PostMapping("/demasquer-artiste")
  public ResponseEntity<Map<String, Object>> demasquerArtiste(
      @RequestBody RequeteMasquerArtiste requete, Authentication auth) {
    if (requete.artisteNorm() == null || requete.artisteNorm().isBlank()) {
      return erreur(400, "Identifiant artiste manquant.");
    }
    spectacleService.demasquerArtiste(utilisateur(auth).id(), requete.artisteNorm());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
