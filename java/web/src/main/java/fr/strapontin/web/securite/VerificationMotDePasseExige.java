/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;

/**
 * Refuse, après vérification du mot de passe, un compte dont un nouveau mot
 * de passe est exigé : l'actuel a pu fuiter, seul le reset par email (preuve
 * de possession de la boîte) lève l'exigence.
 * @since 3.0.2
 * @author Kwaite
 */
public class VerificationMotDePasseExige implements UserDetailsChecker {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC (lecture de l'exigence)
   */
  public VerificationMotDePasseExige(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void check(final UserDetails utilisateur) {
    if (!utilisateur.isCredentialsNonExpired()) {
      throw new CredentialsExpiredException("Mot de passe expiré");
    }
    if (!(utilisateur instanceof UtilisateurDetails details)) {
      return;
    }
    final List<Boolean> exige =
        jdbc.queryForList(
            "SELECT changement_mdp_force_le IS NOT NULL FROM users WHERE id = :id",
            Map.of("id", details.id()),
            Boolean.class);
    if (!exige.isEmpty() && Boolean.TRUE.equals(exige.get(0))) {
      throw new CredentialsExpiredException("Nouveau mot de passe exigé");
    }
  }
}
