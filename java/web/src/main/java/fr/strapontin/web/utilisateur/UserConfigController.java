/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la configuration utilisateur (portage de {@code
/// parametres/+page.server.ts}).
///
/// <p>HTTP pur — délègue toute la logique et l'accès aux données à {@link UserConfigService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/user/config")
public class UserConfigController {

  private final UserConfigService userConfigService;

  /// @param userConfigService service de gestion de la configuration utilisateur
  public UserConfigController(UserConfigService userConfigService) {
    this.userConfigService = userConfigService;
  }

  /// Retourne la configuration de l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return la configuration ou valeurs par défaut si absente
  @GetMapping
  public ResponseEntity<ConfigUtilisateur> lireConfig(Authentication auth) {
    String userId = utilisateur(auth).id();
    return ResponseEntity.ok(userConfigService.lireConfig(userId));
  }

  /// Met à jour les départements de l'utilisateur.
  ///
  /// @param requete corps JSON avec {@code departements} séparés par virgules
  /// @param auth    contexte d'authentification
  /// @return 200 avec {@code succes} et éventuellement {@code ignores}
  @PatchMapping("/departements")
  public ResponseEntity<Map<String, Object>> majDepartements(
      @RequestBody RequeteDepartements requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    List<String> bruts =
        Arrays.stream((requete.departements() == null ? "" : requete.departements()).split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();

    UserConfigService.ResultatMajDepartements resultat =
        userConfigService.majDepartements(userId, bruts);

    Map<String, Object> rep = new HashMap<>();
    rep.put("succes", "departements");
    if (!resultat.ignores().isEmpty()) {
      rep.put("ignores", resultat.ignores());
    }
    return ResponseEntity.ok(rep);
  }

  /// Met à jour les préférences de notification.
  ///
  /// @param requete corps JSON avec fréquence et types de notification
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 en cas d'erreur
  @PatchMapping("/notifications")
  public ResponseEntity<Map<String, Object>> majNotifications(
      @RequestBody RequeteNotifications requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    if (!userConfigService.majNotifications(userId, requete)) {
      return erreur(400, "Valeur invalide.");
    }
    return ResponseEntity.ok(Map.of("succes", "notifications"));
  }

  /// Met à jour le filtre de disponibilité.
  ///
  /// @param requete corps JSON avec {@code filtreDisponibles}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour
  @PatchMapping("/filtres")
  public ResponseEntity<Map<String, Object>> majFiltres(
      @RequestBody RequeteFiltres requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    userConfigService.majFiltres(userId, requete.filtreDisponibles());
    return ResponseEntity.ok(Map.of("succes", "filtres"));
  }

  /// Met à jour le thème de l'interface.
  ///
  /// @param requete corps JSON avec {@code valeur} ({@code auto}, {@code clair} ou {@code sombre})
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si thème invalide
  @PatchMapping("/theme")
  public ResponseEntity<Map<String, Object>> majTheme(
      @RequestBody RequeteTheme requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    if (!userConfigService.majTheme(userId, requete.valeur())) {
      return erreur(400, "Valeur de thème invalide.");
    }
    return ResponseEntity.ok(Map.of("succes", "theme", "theme", requete.valeur()));
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
