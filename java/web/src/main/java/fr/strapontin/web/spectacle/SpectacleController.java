/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import static fr.strapontin.web.ControllerUtils.erreur;
import static fr.strapontin.web.ControllerUtils.parseUuid;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * Contrôleur REST pour les spectacles (portage de {@code spectacles/+page.server.ts}).
 *
 * <p>HTTP pur — délègue toute la logique à {@link SpectacleService}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/spectacles")
public class SpectacleController {

  private final SpectacleService spectacleService;

  /**
   * @param spectacleService service de gestion des spectacles
   */
  public SpectacleController(final SpectacleService spectacleService) {
    this.spectacleService = spectacleService;
  }

  /**
   * Liste les spectacles selon les filtres fournis.
   *
   * @param auth contexte d'authentification
   * @return la liste paginée des spectacles
   */
  @GetMapping
  public ResponseEntity<ReponseSpectacles> listerSpectacles(
      @RequestParam(required = false, defaultValue = "") final String q,
      @RequestParam(required = false, defaultValue = "") final String mois,
      @RequestParam(required = false, defaultValue = "") final String classification,
      @RequestParam(required = false, defaultValue = "") final String genre,
      @RequestParam(required = false, defaultValue = "false") final boolean quinzaine,
      @RequestParam(required = false, defaultValue = "date") final String tri,
      @RequestParam(required = false, defaultValue = "false") final boolean masques,
      @RequestParam(name = "source", required = false) final List<String> sources,
      @RequestParam(required = false) final String indisponibles,
      @RequestParam(required = false, defaultValue = "0") final int page,
      final Authentication auth) {
    final String userId = utilisateur(auth).id();
    final FiltreConcerts filtre =
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

  /**
   * Masque ou démasque un concert pour l'utilisateur courant.
   *
   * @param concertId identifiant UUID du concert
   * @param requete corps JSON avec {@code masquer}
   * @param auth contexte d'authentification
   * @return 200 si mis à jour
   */
  @PatchMapping("/matches/{concertId}")
  public ResponseEntity<Map<String, Object>> modifierMatch(
      @PathVariable final String concertId,
      @Valid @RequestBody final RequeteMasque requete,
      final Authentication auth) {
    spectacleService.modifierMatch(utilisateur(auth).id(), concertId, requete.masquer());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Masque tous les concerts d'un artiste pour l'utilisateur courant.
   *
   * @param requete corps JSON avec {@code artisteId}
   * @param auth contexte d'authentification
   * @return 200 si mis à jour, 400 si artisteId manquant ou invalide
   */
  @PostMapping("/masquer-artiste")
  public ResponseEntity<Map<String, Object>> masquerArtiste(
      @Valid @RequestBody final RequeteMasquerArtiste requete, final Authentication auth) {
    final UUID artisteId = parseUuid(requete.artisteId());
    if (artisteId == null) {
      return erreur(400, "Identifiant artiste manquant ou invalide.");
    }
    spectacleService.masquerArtiste(utilisateur(auth).id(), artisteId);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Démasque tous les concerts d'un artiste pour l'utilisateur courant.
   *
   * @param requete corps JSON avec {@code artisteId}
   * @param auth contexte d'authentification
   * @return 200 si mis à jour, 400 si artisteId manquant ou invalide
   */
  @PostMapping("/demasquer-artiste")
  public ResponseEntity<Map<String, Object>> demasquerArtiste(
      @Valid @RequestBody final RequeteMasquerArtiste requete, final Authentication auth) {
    final UUID artisteId = parseUuid(requete.artisteId());
    if (artisteId == null) {
      return erreur(400, "Identifiant artiste manquant ou invalide.");
    }
    spectacleService.demasquerArtiste(utilisateur(auth).id(), artisteId);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
