/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/// Contrôleur MVC Thymeleaf pour la page d'accueil.
///
/// <p>Délègue le chargement des données à {@link AccueilController}.
/// Gère les actions de masquage via des formulaires POST (PRG pattern).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AccueilViewController {

  private final AccueilController accueilController;
  private final NamedParameterJdbcTemplate jdbc;

  public AccueilViewController(
      AccueilController accueilController, NamedParameterJdbcTemplate jdbc) {
    this.accueilController = accueilController;
    this.jdbc = jdbc;
  }

  /// Affiche la page d'accueil avec les concerts groupés par artiste.
  ///
  /// @param masques       {@code true} pour inclure les concerts masqués
  /// @param indisponibles {@code "1"} pour inclure les concerts non disponibles
  /// @param modele        le modèle Thymeleaf
  /// @param auth          contexte d'authentification
  /// @return le nom du template
  @GetMapping("/")
  public String accueil(
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(required = false) String indisponibles,
      Model modele,
      Authentication auth) {
    ResponseEntity<?> rep = accueilController.accueil(masques, indisponibles, auth);
    modele.addAttribute("donnees", rep.getBody());
    modele.addAttribute("masques", masques);
    modele.addAttribute("indisponibles", "1".equals(indisponibles));
    modele.addAttribute("currentPage", "accueil");
    return "accueil";
  }

  /// Masque un concert pour l'utilisateur courant.
  ///
  /// @param concertId l'identifiant du concert
  /// @param auth      contexte d'authentification
  /// @return redirect vers la page d'accueil (PRG)
  @PostMapping("/masquer/{concertId}")
  public String masquer(@PathVariable String concertId, Authentication auth) {
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = NOW()
        WHERE concert_id = :concertId AND user_id = :userId AND masque_le IS NULL
        """,
        Map.of("concertId", concertId, "userId", utilisateur(auth).id()));
    return "redirect:/";
  }

  /// Masque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param artisteNorm l'artiste normalisé
  /// @param auth        contexte d'authentification
  /// @return redirect vers la page d'accueil (PRG)
  @PostMapping("/masquer-artiste")
  public String masquerArtiste(@RequestParam String artisteNorm, Authentication auth) {
    String userId = utilisateur(auth).id();
    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("artisteNorm", artisteNorm);
    params.put("aujourd8", LocalDate.now());
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = NOW()
        WHERE user_id = :userId AND masque_le IS NULL
          AND concert_id IN (
            SELECT id FROM concerts
            WHERE artiste_norm = :artisteNorm AND date >= :aujourd8
          )
        """,
        params);
    return "redirect:/";
  }

  /// Démasque un concert pour l'utilisateur courant.
  ///
  /// @param concertId l'identifiant du concert
  /// @param auth      contexte d'authentification
  /// @return redirect vers la page d'accueil (PRG)
  @PostMapping("/demasquer/{concertId}")
  public String demasquer(@PathVariable String concertId, Authentication auth) {
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = NULL
        WHERE concert_id = :concertId AND user_id = :userId
        """,
        Map.of("concertId", concertId, "userId", utilisateur(auth).id()));
    return "redirect:/";
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
