/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.setup;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint REST de configuration initiale de l'application.
///
/// <p>HTTP pur — délègue toute la logique à {@link SetupService}.
/// Accessible sans authentification ({@code permitAll}).
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/setup")
public class SetupController {

  private final SetupService setupService;

  /// @param setupService service d'initialisation de l'application
  public SetupController(SetupService setupService) {
    this.setupService = setupService;
  }

  /// Vérifie si l'application est déjà configurée.
  ///
  /// @return {@code { "deja_configure": true }} si au moins un utilisateur existe
  @GetMapping
  public ResponseEntity<ReponseSetup> verifierSetup() {
    return ResponseEntity.ok(new ReponseSetup(setupService.estDejaConfiguree()));
  }

  /// Crée le premier administrateur et initialise la configuration applicative.
  ///
  /// @param requete les données d'initialisation
  /// @return 201 si créé, 400 si validation échoue, 409 si déjà configuré
  @PostMapping
  public ResponseEntity<Map<String, Object>> initialiser(@Valid @RequestBody RequeteSetup requete) {
    String erreur = setupService.initialiser(requete);
    if (erreur != null) {
      int status = erreur.contains("déjà configurée") ? 409 : 400;
      return erreur(status, erreur);
    }
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
