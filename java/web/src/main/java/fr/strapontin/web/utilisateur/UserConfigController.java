/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour la configuration utilisateur (portage de {@code
 * parametres/+page.server.ts}).
 *
 * <p>HTTP pur — délègue toute la logique et l'accès aux données à {@link UserConfigService}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/user/config")
public class UserConfigController {

  private final UserConfigService userConfigService;

  /**
   * @param userConfigService service de gestion de la configuration utilisateur
   */
  public UserConfigController(final UserConfigService userConfigService) {
    this.userConfigService = userConfigService;
  }

  /**
   * Retourne la configuration de l'utilisateur courant.
   *
   * @param auth contexte d'authentification
   * @return la configuration ou valeurs par défaut si absente
   */
  @GetMapping
  public ResponseEntity<ConfigUtilisateur> lireConfig(final Authentication auth) {
    final String userId = utilisateur(auth).id();
    return ResponseEntity.ok(userConfigService.lireConfig(userId));
  }

  /**
   * Met à jour les départements de l'utilisateur.
   *
   * @param requete corps JSON avec {@code departements} séparés par virgules
   * @param auth contexte d'authentification
   * @return 200 avec {@code succes} et éventuellement {@code ignores}
   */
  @PatchMapping("/departements")
  public ResponseEntity<Map<String, Object>> majDepartements(
      @Valid @RequestBody final RequeteDepartements requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();

    final String brut = requete.departements() == null ? "" : requete.departements();
    final List<String> bruts =
        Arrays.stream(brut.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();

    final UserConfigService.ResultatMajDepartements resultat =
        userConfigService.majDepartements(userId, bruts);

    final Map<String, Object> rep = new HashMap<>();
    rep.put("succes", "departements");
    if (!resultat.ignores().isEmpty()) {
      rep.put("ignores", resultat.ignores());
    }
    return ResponseEntity.ok(rep);
  }

  /**
   * Met à jour les préférences de notification.
   *
   * @param requete corps JSON avec fréquence et types de notification
   * @param auth contexte d'authentification
   * @return 200 si mis à jour — 400 si invalide (via {@link GlobalExceptionHandler})
   */
  @PatchMapping("/notifications")
  public ResponseEntity<Map<String, Object>> majNotifications(
      @Valid @RequestBody final RequeteNotifications requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    userConfigService.majNotifications(userId, requete);
    return ResponseEntity.ok(Map.of("succes", "notifications"));
  }

  /**
   * Met à jour le filtre de disponibilité.
   *
   * @param requete corps JSON avec {@code filtreDisponibles}
   * @param auth contexte d'authentification
   * @return 200 si mis à jour
   */
  @PatchMapping("/filtres")
  public ResponseEntity<Map<String, Object>> majFiltres(
      @Valid @RequestBody final RequeteFiltres requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    userConfigService.majFiltres(userId, requete.filtreDisponibles());
    return ResponseEntity.ok(Map.of("succes", "filtres"));
  }

  /**
   * Met à jour le thème de l'interface.
   *
   * @param requete corps JSON avec {@code valeur} ({@code auto}, {@code clair} ou {@code sombre})
   * @param auth contexte d'authentification
   * @return 200 si mis à jour — 400 si invalide (via {@link GlobalExceptionHandler})
   */
  @PatchMapping("/theme")
  public ResponseEntity<Map<String, Object>> majTheme(
      @Valid @RequestBody final RequeteTheme requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    userConfigService.majTheme(userId, requete.valeur());
    return ResponseEntity.ok(Map.of("succes", "theme", "theme", requete.valeur()));
  }

  /**
   * Indique si l'utilisateur n'a pas encore vu la modale d'onboarding.
   *
   * @param auth contexte d'authentification
   * @return {@code {"nonVu": true|false}}
   */
  @GetMapping("/onboarding")
  public ResponseEntity<Map<String, Object>> onboarding(final Authentication auth) {
    final String userId = utilisateur(auth).id();
    return ResponseEntity.ok(Map.of("nonVu", userConfigService.onboardingNonVu(userId)));
  }

  /**
   * Marque la modale d'onboarding comme vue pour l'utilisateur courant.
   *
   * @param auth contexte d'authentification
   * @return 204 No Content
   */
  @PostMapping("/onboarding-vu")
  public ResponseEntity<Void> marquerOnboardingVu(final Authentication auth) {
    final String userId = utilisateur(auth).id();
    userConfigService.marquerOnboardingVu(userId);
    return ResponseEntity.noContent().build();
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
