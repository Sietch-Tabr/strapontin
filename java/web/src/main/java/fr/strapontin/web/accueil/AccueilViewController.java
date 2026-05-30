/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.securite.UtilisateurDetails;
import fr.strapontin.web.utilisateur.UserConfigService;
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
/// Délègue le chargement des données à [AccueilController].
/// Gère les actions de masquage via des formulaires POST (PRG pattern).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AccueilViewController {

  private static final String REDIRECT_ACCUEIL = "redirect:/";

  private final AccueilController accueilController;
  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  public AccueilViewController(
      AccueilController accueilController,
      NamedParameterJdbcTemplate jdbc,
      UserConfigService userConfigService) {
    this.accueilController = accueilController;
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  /// Affiche la page d'accueil avec les concerts groupés par artiste.
  ///
  /// Marque tous les matches non encore vus comme vus (`vu_le = now()`)
  /// afin de réinitialiser le badge de navigation.
  ///
  /// @param masques       `true` pour inclure les concerts masqués
  /// @param indisponibles {@code "1"} pour inclure les concerts non disponibles
  /// @param nouveau       `true` pour n'afficher que les concerts non encore vus
  /// @param modele        le modèle Thymeleaf
  /// @param auth          contexte d'authentification
  /// @return le nom du template
  @GetMapping("/")
  public String accueil(
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(required = false) String indisponibles,
      @RequestParam(required = false, defaultValue = "false") boolean nouveau,
      Model modele,
      Authentication auth) {
    String userId = utilisateur(auth).id();
    jdbc.update(
        "UPDATE user_matches SET vu_le = now() WHERE user_id = :userId AND vu_le IS NULL",
        Map.of("userId", userId));
    boolean filtreDisponibles = userConfigService.lireFiltreDisponibles(userId);
    boolean inclureIndisponibles =
        indisponibles != null ? "1".equals(indisponibles) : !filtreDisponibles;
    ResponseEntity<?> rep = accueilController.accueil(masques, indisponibles, nouveau, auth);
    modele.addAttribute("donnees", rep.getBody());
    modele.addAttribute("masques", masques);
    modele.addAttribute("indisponibles", inclureIndisponibles);
    modele.addAttribute("nouveau", nouveau);
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
        WHERE concert_id = CAST(:concertId AS uuid) AND user_id = :userId AND masque_le IS NULL
        """,
        Map.of("concertId", concertId, "userId", utilisateur(auth).id()));
    return REDIRECT_ACCUEIL;
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
    return REDIRECT_ACCUEIL;
  }

  /// Démasque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param artisteNorm l'artiste normalisé
  /// @param auth        contexte d'authentification
  /// @return redirect vers la page d'accueil (PRG)
  @PostMapping("/demasquer-artiste")
  public String demasquerArtiste(@RequestParam String artisteNorm, Authentication auth) {
    String userId = utilisateur(auth).id();
    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("artisteNorm", artisteNorm);
    params.put("aujourd8", LocalDate.now());
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = NULL
        WHERE user_id = :userId
          AND concert_id IN (
            SELECT id FROM concerts
            WHERE artiste_norm = :artisteNorm AND date >= :aujourd8
          )
        """,
        params);
    return REDIRECT_ACCUEIL;
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
        WHERE concert_id = CAST(:concertId AS uuid) AND user_id = :userId
        """,
        Map.of("concertId", concertId, "userId", utilisateur(auth).id()));
    return REDIRECT_ACCUEIL;
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }
}
