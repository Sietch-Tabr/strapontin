/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Chargement des utilisateurs depuis la base de données pour Spring Security.
 *
 * <p>Charge l'utilisateur depuis la table {@code users} jointe à {@code accounts}
 * (provider {@code credential}). Le login est insensible à la casse
 * (correspondance sur {@code lower(username)}).
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class UtilisateurDetailsService implements UserDetailsService {

  private static final Logger log = LoggerFactory.getLogger(UtilisateurDetailsService.class);

  private final NamedParameterJdbcTemplate jdbc;

  public UtilisateurDetailsService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Charge un utilisateur par son identifiant (insensible à la casse).
   *
   * @param identifiant le nom d'utilisateur
   * @return les détails Spring Security de l'utilisateur
   * @throws UsernameNotFoundException si aucun compte n'est trouvé
   */
  @Override
  public UserDetails loadUserByUsername(final String identifiant) throws UsernameNotFoundException {
    final String sql =
        """
            SELECT u.id, u.username, u.email, u.role,
                   u.two_factor_enabled, u.must_change_credentials,
                   a.password
            FROM users u
            JOIN accounts a ON a.user_id = u.id AND a.provider_id = 'credential'
            WHERE lower(u.username) = lower(:identifiant)
            """;

    return jdbc.query(
        sql,
        Map.of("identifiant", identifiant),
        rs -> {
          if (!rs.next()) {
            log.debug("Identifiant non trouvé : {}", identifiant);
            throw new UsernameNotFoundException("Identifiant ou mot de passe incorrect");
          }
          return new UtilisateurDetails(
              rs.getString("id"),
              rs.getString("username"),
              rs.getString("email"),
              rs.getString("password"),
              rs.getString("role"),
              rs.getBoolean("two_factor_enabled"),
              rs.getBoolean("must_change_credentials"));
        });
  }
}
