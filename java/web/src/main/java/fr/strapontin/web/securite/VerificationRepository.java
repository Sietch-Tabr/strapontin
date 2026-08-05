/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
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
    return !jdbc.queryForList(
            "SELECT id FROM users WHERE email = :email", Map.of("email", email), String.class)
        .isEmpty();
  }

  /// Supprime les entrées de vérification pour un identifiant donné.
  ///
  /// @param identifiant préfixe/identifiant de la vérification (ex. {@code
  // "reset:user@example.com"})
  public void supprimerParIdentifiant(String identifiant) {
    jdbc.update(
        "DELETE FROM verifications WHERE identifier = :prefixe", Map.of("prefixe", identifiant));
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
  /// @return l'identifiant (ex. {@code "reset:email"}), ou {@link Optional#empty()} si
  // invalide/expiré
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

  /// Définit l'identifiant et désactive le flag {@code must_change_credentials} par email.
  ///
  /// <p>Ne resynchronise {@code display_username} sur le nouvel identifiant que s'il n'a jamais
  /// été personnalisé par un admin, même garde que
  /// {@link fr.strapontin.web.utilisateur.UtilisateurRepository#majIdentifiant}.
  ///
  /// @param email      email de l'utilisateur
  /// @param identifiant nouvel identifiant (déjà normalisé en minuscules)
  public void majUsernameEtActiverParEmail(String email, String identifiant) {
    jdbc.update(
        """
        UPDATE users
        SET username = :ident,
            display_username = CASE WHEN display_username = username THEN :ident
                                     ELSE display_username END,
            must_change_credentials = false, updated_at = NOW()
        WHERE lower(email) = lower(:email)
        """,
        Map.of("ident", identifiant, "email", email));
  }

  /// Retourne le nom de connexion (username) d'un utilisateur à partir de son email.
  ///
  /// @param email email de l'utilisateur (normalisé)
  /// @return le username, ou {@link Optional#empty()} si l'utilisateur n'existe pas ou n'a
  // pas de username
  public Optional<String> lireUsernameParEmail(String email) {
    List<String> lignes =
        jdbc.queryForList(
            "SELECT username FROM users WHERE lower(email) = lower(:email)",
            Map.of("email", email),
            String.class);
    return lignes.isEmpty() || lignes.get(0) == null
        ? Optional.empty()
        : Optional.of(lignes.get(0));
  }

  /// Vérifie si un identifiant est déjà pris par un autre utilisateur (exclusion par email).
  ///
  /// @param identifiant identifiant à vérifier (en minuscules)
  /// @param email       email de l'utilisateur courant (exclu)
  /// @return {@code true} si l'identifiant est pris
  public boolean identifiantDejaUtiliseParEmail(String identifiant, String email) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE lower(username) = :ident AND lower(email) != lower(:email)",
            Map.of("ident", identifiant, "email", email),
            Integer.class)
        .isEmpty();
  }
}
