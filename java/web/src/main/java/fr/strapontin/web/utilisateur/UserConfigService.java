/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service d'accès aux préférences utilisateur stockées dans {@code user_config}.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class UserConfigService {

  private final NamedParameterJdbcTemplate jdbc;

  public UserConfigService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Lit le filtre "concerts disponibles uniquement" de l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return {@code true} si seuls les concerts disponibles doivent être affichés (défaut)
  public boolean lireFiltreDisponibles(String userId) {
    List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT filtre_disponibles FROM user_config WHERE user_id = :userId",
            Map.of("userId", userId),
            Boolean.class);
    return lignes.isEmpty() || lignes.get(0);
  }
}
