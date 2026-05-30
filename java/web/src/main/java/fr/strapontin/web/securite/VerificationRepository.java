/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/// Accès aux données de la table {@code verifications} (tokens reset MDP et invitation).
///
/// <p>SQL pur — aucune logique métier. Utilisé par {@link AuthService} et
/// {@link fr.strapontin.web.admin.AdminService}.
///
/// @since 2.1.0
/// @author Kwaite
@Repository
public class VerificationRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc accès JDBC
  public VerificationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Vérifie si un email correspond à un utilisateur existant.
  ///
  /// @param email adresse email (normalisée)
  /// @return {@code true} si un utilisateur existe avec cet email
  public boolean utilisateurExistePourEmail(String email) {
    return !jdbc
        .queryForList(
            "SELECT id FROM users WHERE email = :email", Map.of("email", email), String.class)
        .isEmpty();
  }

  /// Supprime les entrées de vérification pour un identifiant donné.
  ///
  /// @param identifiant préfixe/identifiant de la vérification (ex. {@code "reset:user@example.com"})
  public void supprimerParIdentifiant(String identifiant) {
    jdbc.update(
        "DELETE FROM verifications WHERE identifier = :prefixe",
        Map.of("prefixe", identifiant));
  }

  /// Insère une entrée de vérification.
  ///
  /// @param id         identifiant UUID
  /// @param identifiant contexte (ex. {@code "reset:email"}, {@code "invite:email"})
  /// @param tokenHash  hash SHA-256 du token
  /// @param expiresAt  date d'expiration
  public void inserer(String id, String identifiant, String tokenHash, Instant expiresAt) {
    jdbc.update(
        """
        INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
        VALUES (:id, :identifier, :value, :expiresAt, now(), now())
        """,
        Map.of(
            "id", id,
            "identifier", identifiant,
            "value", tokenHash,
            "expiresAt", Timestamp.from(expiresAt)));
  }

  /// Cherche l'identifiant associé à un token hash non expiré.
  ///
  /// @param tokenHash hash SHA-256 du token
  /// @return l'identifiant (ex. {@code "reset:email"}), ou {@link Optional#empty()} si invalide/expiré
  public Optional<String> lireIdentifiantParTokenHash(String tokenHash) {
    List<String> lignes =
        jdbc.queryForList(
            "SELECT identifier FROM verifications WHERE value = :tokenHash AND expires_at > NOW()",
            Map.of("tokenHash", tokenHash),
            String.class);
    return lignes.isEmpty() ? Optional.empty() : Optional.of(lignes.get(0));
  }

  /// Supprime une entrée de vérification par hash de token.
  ///
  /// @param tokenHash hash SHA-256 du token
  public void supprimerParTokenHash(String tokenHash) {
    jdbc.update(
        "DELETE FROM verifications WHERE value = :tokenHash", Map.of("tokenHash", tokenHash));
  }

  /// Met à jour le mot de passe dans {@code accounts} par email utilisateur.
  ///
  /// @param email email de l'utilisateur
  /// @param hash  nouveau hash bcrypt
  public void majMotDePasseParEmail(String email, String hash) {
    jdbc.update(
        """
        UPDATE accounts SET password = :hash, updated_at = NOW()
        WHERE user_id = (SELECT id FROM users WHERE email = :email)
        """,
        Map.of("hash", hash, "email", email));
  }

  /// Lève le flag {@code must_change_credentials} pour un utilisateur par email.
  ///
  /// @param email email de l'utilisateur
  public void majMustChangeFalseParEmail(String email) {
    jdbc.update(
        "UPDATE users SET must_change_credentials = false, updated_at = NOW() WHERE email = :email",
        Map.of("email", email));
  }
}
