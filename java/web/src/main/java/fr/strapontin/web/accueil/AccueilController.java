/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.securite.UtilisateurDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la page d'accueil — concerts groupés par artiste suivi.
///
/// <p>HTTP pur — délègue toute la logique à {@link AccueilService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/accueil")
public class AccueilController {

  private final AccueilService accueilService;

  /// @param accueilService service de la page d'accueil
  public AccueilController(AccueilService accueilService) {
    this.accueilService = accueilService;
  }

  /// Retourne les prochains concerts des artistes et salles suivis, groupés par artiste.
  ///
  /// @param masques       {@code true} pour afficher les concerts masqués
  /// @param indisponibles {@code "1"} pour inclure les concerts non disponibles
  /// @param nouveau       {@code true} pour n'afficher que les concerts non encore vus
  /// @param auth          contexte d'authentification
  /// @return les groupes artiste/concerts avec compteurs
  @GetMapping
  public ResponseEntity<ReponseAccueil> accueil(
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(required = false) String indisponibles,
      @RequestParam(required = false, defaultValue = "false") boolean nouveau,
      Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    return ResponseEntity.ok(accueilService.accueil(userId, masques, indisponibles, nouveau));
  }
}
