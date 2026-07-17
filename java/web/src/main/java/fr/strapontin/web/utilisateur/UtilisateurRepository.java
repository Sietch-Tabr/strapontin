/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
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

  /// Vérifie si un identifiant est déjà pris par un autre utilisateur.
  ///
  /// @param identifiant identifiant à vérifier (en minuscules)
  /// @param userId      identifiant de l'utilisateur courant (exclu)
  /// @return {@code true} si l'identifiant est pris
  boolean identifiantDejaUtilise(String identifiant, String userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE lower(username) = :ident AND id != :userId",
            Map.of("ident", identifiant, CLE_USER_ID, userId),
            Integer.class)
        .isEmpty();
  }

  /// Met à jour l'identifiant (username).
  ///
  /// <p>Ne resynchronise {@code display_username} sur le nouvel identifiant que s'il n'a jamais
  /// été personnalisé par un admin (#814) — sinon un nom affiché personnalisé
  /// ({@link fr.strapontin.web.admin.AdminService}, champ {@code nom}) serait écrasé
  /// silencieusement à chaque changement d'identifiant par l'utilisateur lui-même.
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param identifiant nouvel identifiant (déjà validé, en minuscules)
  void majIdentifiant(String userId, String identifiant) {
    jdbc.update(
        """
        UPDATE users
        SET username = :ident,
            display_username = CASE WHEN display_username = username THEN :ident
                                     ELSE display_username END,
            updated_at = now()
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
        "UPDATE accounts SET password = :hash WHERE user_id = :userId AND provider_id ="
            + " 'credential'",
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

  /// Retourne le nombre total d'administrateurs sur l'instance.
  ///
  /// @return nombre d'utilisateurs ayant le rôle {@code admin}
  int compterAdmins() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin'", Map.of(), Integer.class);
    return n == null ? 0 : n;
  }

  /// Supprime un utilisateur (cascade sur toutes ses données).
  ///
  /// @param userId identifiant de l'utilisateur à supprimer
  void supprimer(String userId) {
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
  }

  /// Compte les concerts non vus/masqués pour un artiste suivi (badge "nouveau" de navigation).
  ///
  /// <p>Extrait de {@code securite.SessionController} (#918) — le contrôleur exécutait ce SQL
  /// directement, en violation du pattern Controller (HTTP pur) → Service → Repository.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return nombre de concerts non vus pour les artistes suivis
  int compterNouveauxMatchesArtistes(String userId) {
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
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    return count != null ? count : 0;
  }

  /// Données email lues depuis la base (utilisées pour construire {@link ProfilUtilisateur}).
  record EmailsUtilisateur(String email, String pendingEmail) {}
}
