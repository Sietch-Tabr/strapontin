/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Retourne les données de session nécessaires au layout Vue (nom, rôle, badge).
///
/// @since 2.2.0
/// @author Kwaite
@RestController
public class SessionController {

  private final NamedParameterJdbcTemplate jdbc;

  public SessionController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Retourne les données de navigation de l'utilisateur courant.
  ///
  /// @param auth l'authentification Spring Security
  /// @return les données de session : rôle, nbNouveau
  @GetMapping("/api/session")
  public SessionDto session(Authentication auth) {
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      return new SessionDto("user", 0);
    }
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM user_matches um
            JOIN concerts c ON c.id = um.concert_id
            WHERE um.user_id = :userId
              AND um.vu_le IS NULL
              AND um.masque_le IS NULL
              AND EXISTS (
                SELECT 1 FROM user_artistes ua
                WHERE ua.user_id = :userId
                  AND c.artiste_id = ua.artiste_id
              )
            """,
            Map.of("userId", ud.id()),
            Integer.class);
    return new SessionDto(ud.role(), count != null ? count : 0);
  }

  /// DTO de session pour le layout Vue.
  ///
  /// @param role      le rôle ({@code user} ou {@code admin})
  /// @param nbNouveau le nombre de concerts non encore vus
  public record SessionDto(String role, int nbNouveau) {}
}
