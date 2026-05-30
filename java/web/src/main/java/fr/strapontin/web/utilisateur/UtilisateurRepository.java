/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/// Accès aux données pour les utilisateurs ({@code users}, {@code accounts}).
///
/// <p>SQL pur — aucune logique métier. Les validations (format, unicité métier) appartiennent aux
/// couches supérieures.
///
/// @since 2.1.0
/// @author Kwaite
@Repository
class UtilisateurRepository {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  UtilisateurRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Lit email et pending_email depuis la base.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return emails, ou {@link Optional#empty()} si l'utilisateur n'existe pas
  Optional<EmailsUtilisateur> lireEmails(String userId) {
    return jdbc
        .query(
            "SELECT email, pending_email FROM users WHERE id = :id",
            Map.of("id", userId),
            (rs, _) -> new EmailsUtilisateur(rs.getString("email"), rs.getString("pending_email")))
        .stream()
        .findFirst();
  }

  /// Vérifie si un email est déjà utilisé par un autre utilisateur.
  ///
  /// @param email  email à vérifier
  /// @param userId identifiant de l'utilisateur courant (exclu de la vérification)
  /// @return {@code true} si l'email est pris
  boolean emailDejaUtilise(String email, String userId) {
    return !jdbc
        .queryForList(
            "SELECT 1 FROM users WHERE email = :email AND id != :userId",
            Map.of("email", email, CLE_USER_ID, userId),
            Integer.class)
        .isEmpty();
  }

  /// Met à jour le nom d'affichage.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param nom    nouveau nom (déjà validé)
  void majNom(String userId, String nom) {
    jdbc.update(
        "UPDATE users SET name = :nom, updated_at = now() WHERE id = :userId",
        Map.of("nom", nom, CLE_USER_ID, userId));
  }

  /// Met à jour l'email.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param email  nouvel email (déjà validé et normalisé)
  void majEmail(String userId, String email) {
    jdbc.update(
        "UPDATE users SET email = :email, updated_at = now() WHERE id = :userId",
        Map.of("email", email, CLE_USER_ID, userId));
  }

  /// Vérifie si un identifiant est déjà pris par un autre utilisateur.
  ///
  /// @param identifiant identifiant à vérifier (en minuscules)
  /// @param userId      identifiant de l'utilisateur courant (exclu)
  /// @return {@code true} si l'identifiant est pris
  boolean identifiantDejaUtilise(String identifiant, String userId) {
    return !jdbc
        .queryForList(
            "SELECT 1 FROM users WHERE lower(username) = :ident AND id != :userId",
            Map.of("ident", identifiant, CLE_USER_ID, userId),
            Integer.class)
        .isEmpty();
  }

  /// Met à jour l'identifiant (username).
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param identifiant nouvel identifiant (déjà validé, en minuscules)
  void majIdentifiant(String userId, String identifiant) {
    jdbc.update(
        """
        UPDATE users SET username = :ident, display_username = :ident, updated_at = now()
        WHERE id = :userId
        """,
        Map.of("ident", identifiant, CLE_USER_ID, userId));
  }

  /// Met à jour le hash du mot de passe dans {@code accounts}.
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param nouveauHash hash bcrypt du nouveau mot de passe
  void changerMotDePasse(String userId, String nouveauHash) {
    jdbc.update(
        "UPDATE accounts SET password = :hash WHERE user_id = :userId AND provider_id = 'credential'",
        Map.of("hash", nouveauHash, CLE_USER_ID, userId));
  }

  /// Active le TOTP en persistant le secret.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param secret secret Base32 TOTP
  void activerTotp(String userId, String secret) {
    jdbc.update(
        "UPDATE users SET two_factor_secret = :secret, two_factor_enabled = true WHERE id = :id",
        Map.of("secret", secret, "id", userId));
  }

  /// Retourne le secret TOTP stocké en base.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return secret Base32, ou {@code null} si absent
  String lireSecretTotp(String userId) {
    List<String> lignes =
        jdbc.queryForList(
            "SELECT two_factor_secret FROM users WHERE id = :id",
            Map.of("id", userId),
            String.class);
    return lignes.isEmpty() ? null : lignes.get(0);
  }

  /// Désactive le TOTP (efface le secret).
  ///
  /// @param userId identifiant de l'utilisateur
  void desactiverTotp(String userId) {
    jdbc.update(
        "UPDATE users SET two_factor_secret = NULL, two_factor_enabled = false WHERE id = :id",
        Map.of("id", userId));
  }

  /// Met à jour les champs du premier login (nom, identifiant, flag must_change_credentials).
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param nom         nouveau nom
  /// @param identifiant nouvel identifiant (en minuscules)
  void majPremierLogin(String userId, String nom, String identifiant) {
    jdbc.update(
        """
        UPDATE users SET name = :nom, username = :ident, display_username = :ident,
               must_change_credentials = false, updated_at = now()
        WHERE id = :userId
        """,
        Map.of("nom", nom, "ident", identifiant, CLE_USER_ID, userId));
  }

  /// Supprime un utilisateur (cascade sur toutes ses données).
  ///
  /// @param userId identifiant de l'utilisateur à supprimer
  void supprimer(String userId) {
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
  }

  /// Données email lues depuis la base (utilisées pour construire {@link ProfilUtilisateur}).
  record EmailsUtilisateur(String email, String pendingEmail) {}
}
