/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.spectacle;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/// Contrôleur MVC Thymeleaf pour la page des spectacles.
///
/// <p>Délègue le chargement à {@link SpectacleController}. Le masquage/démasquage
/// passe par des formulaires POST (PRG pattern).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class SpectacleViewController {

  private final SpectacleController spectacleController;
  private final NamedParameterJdbcTemplate jdbc;

  public SpectacleViewController(
      SpectacleController spectacleController, NamedParameterJdbcTemplate jdbc) {
    this.spectacleController = spectacleController;
    this.jdbc = jdbc;
  }

  /// Affiche la liste des spectacles avec filtres et pagination.
  ///
  /// @param q               terme de recherche artiste
  /// @param mois            filtre mois (YYYY-MM)
  /// @param classification  filtre classification
  /// @param genre           filtre genre
  /// @param quinzaine       {@code true} pour les 15 prochains jours
  /// @param tri             {@code "date"} ou {@code "nouveaute"}
  /// @param masques         {@code true} pour afficher les concerts masqués
  /// @param sources         sources à inclure ({@code artistes} / {@code salles})
  /// @param indisponibles   {@code "1"} pour inclure les concerts non disponibles
  /// @param page            numéro de page (0-indexé)
  /// @param modele          le modèle Thymeleaf
  /// @param auth            contexte d'authentification
  /// @return le nom du template
  @GetMapping("/spectacles")
  public String spectacles(
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
      Model modele,
      Authentication auth) {
    var rep =
        spectacleController.listerSpectacles(
            q,
            mois,
            classification,
            genre,
            quinzaine,
            tri,
            masques,
            sources,
            indisponibles,
            page,
            auth);
    modele.addAttribute("donnees", rep.getBody());
    modele.addAttribute("q", q);
    modele.addAttribute("mois", mois);
    modele.addAttribute("classification", classification);
    modele.addAttribute("genre", genre);
    modele.addAttribute("tri", tri);
    modele.addAttribute("masques", masques);
    modele.addAttribute("sources", sources != null ? sources : List.of());
    modele.addAttribute("indisponibles", indisponibles);
    modele.addAttribute("quinzaine", quinzaine);
    modele.addAttribute("page", page);
    modele.addAttribute("currentPage", "spectacles");
    return "spectacles";
  }

  /// Démasque un concert pour l'utilisateur courant.
  ///
  /// @param concertId l'identifiant du concert
  /// @param auth      contexte d'authentification
  /// @return redirect vers /spectacles (PRG)
  @PostMapping("/spectacles/demasquer/{concertId}")
  public String demasquer(@PathVariable String concertId, Authentication auth) {
    jdbc.update(
        "UPDATE user_matches SET masque_le = NULL WHERE user_id = :userId AND concert_id = CAST(:id"
            + " AS uuid)",
        Map.of("userId", utilisateur(auth).id(), "id", concertId));
    return "redirect:/spectacles";
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
