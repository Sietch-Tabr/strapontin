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
 * Contrôle postérieur à la vérification du mot de passe : refuse la connexion d'un compte dont un
 * nouveau mot de passe est exigé (communication d'incident, {@code
 * users.changement_mdp_force_le}). Le mot de passe actuel a pu fuiter : seule la réinitialisation
 * par email, qui prouve la possession de la boîte, lève l'exigence.
 *
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
