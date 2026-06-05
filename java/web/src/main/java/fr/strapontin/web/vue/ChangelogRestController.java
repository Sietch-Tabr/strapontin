/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour le journal des modifications.
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/changelog")
public class ChangelogRestController {

  private static final String VERSION_COURANTE = "2.1.3";

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc template JDBC nommé
  public ChangelogRestController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Marque la version courante du changelog comme vue pour l'utilisateur.
  ///
  /// @param auth contexte d'authentification
  /// @return 204 No Content
  @PostMapping("/marquer-vue")
  public ResponseEntity<Void> marquerVue(Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    jdbc.update(
        "UPDATE user_config SET changelog_version_vue = :v WHERE user_id = :u",
        Map.of("v", VERSION_COURANTE, "u", userId));
    return ResponseEntity.noContent().build();
  }
}
