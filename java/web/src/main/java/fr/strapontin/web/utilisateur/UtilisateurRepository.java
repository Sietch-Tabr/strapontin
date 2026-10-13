/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.core.ConfigCipher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès aux données pour les utilisateurs ({@code users},
 * {@code accounts}) — SQL pur, aucune logique métier.
 * @since 2.1.0
 * @author Kwaite
 */
@Repository
class UtilisateurRepository {

  private static final Logger LOG = LoggerFactory.getLogger(UtilisateurRepository.class);
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigCipher cipher;

  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  UtilisateurRepository(
      final NamedParameterJdbcTemplate jdbc,
      @Value("${app.config.secret}") final String secretConfig,
      @Value("${app.config.secret-precedent:}") final String secretPrecedent) {
    this.jdbc = jdbc;
    this.cipher = new ConfigCipher(secretConfig, secretPrecedent);
  }

  /**
   * Lit email et pending_email depuis la base.
   *
   * @param userId identifiant de l'utilisateur
   * @return emails, ou {@link Optional#empty()} si l'utilisateur n'existe pas
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  Optional<EmailsUtilisateur> lireEmails(final String userId) {
    return jdbc
        .query(
            "SELECT email, pending_email FROM users WHERE id = :id",
            Map.of("id", userId),
            (rs, _) -> new EmailsUtilisateur(rs.getString("email"), rs.getString("pending_email")))
        .stream()
        .findFirst();
  }

  /**
   * Vérifie si un identifiant est déjà pris par un autre utilisateur.
   *
   * @param identifiant identifiant à vérifier (en minuscules)
   * @param userId identifiant de l'utilisateur courant (exclu)
   * @return {@code true} si l'identifiant est pris
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  boolean identifiantDejaUtilise(final String identifiant, final String userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE lower(username) = :ident AND id != :userId",
            Map.of("ident", identifiant, CLE_USER_ID, userId),
            Integer.class)
        .isEmpty();
  }

  /**
   * Met à jour l'identifiant (username) (détail : CLAUDE.md,
   * Authentification — display_username).
   * @param userId identifiant de l'utilisateur
   * @param identifiant nouvel identifiant (déjà validé, minuscules)
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  void majIdentifiant(final String userId, final String identifiant) {
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

  /**
   * Met à jour le hash du mot de passe dans {@code accounts}.
   *
   * @param userId identifiant de l'utilisateur
   * @param nouveauHash hash bcrypt du nouveau mot de passe — ne lève pas un
   *     changement exigé après incident, réservé à la réinitialisation par
   *     email (seule preuve de possession du compte si le mot de passe a fui)
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  void changerMotDePasse(final String userId, final String nouveauHash) {
    jdbc.update(
        "UPDATE accounts SET password = :hash WHERE user_id = :userId AND provider_id ="
            + " 'credential'",
        Map.of("hash", nouveauHash, CLE_USER_ID, userId));
  }

  /**
   * @param userId identifiant de l'utilisateur
   * @return {@code true} si un nouveau mot de passe est exigé (communication
   *     d'incident)
   */
  /* package-private : accès SQL réservé à ParametresService (même package). */
  boolean estChangementMotDePasseExige(final String userId) {
    final List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT changement_mdp_force_le IS NOT NULL FROM users WHERE id = :userId",
            Map.of(CLE_USER_ID, userId),
            Boolean.class);
    return !lignes.isEmpty() && Boolean.TRUE.equals(lignes.get(0));
  }

  /**
   * Active le TOTP en persistant le secret, chiffré (AES-256-GCM, même clé que
   * {@code app_config} ).
   *
   * @param userId identifiant de l'utilisateur
   * @param secret secret Base32 TOTP en clair
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  void activerTotp(final String userId, final String secret) {
    jdbc.update(
        "UPDATE users SET two_factor_secret = :secret, two_factor_enabled = true WHERE id = :id",
        Map.of("secret", cipher.chiffrer(secret), "id", userId));
  }

  /**
   * Retourne le secret TOTP stocké en base, déchiffré (détail :
   * CLAUDE.md, Sécurité admin/sessions).
   * @param userId identifiant de l'utilisateur
   * @return secret Base32 en clair, ou {@code null} si absent
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  String lireSecretTotp(final String userId) {
    final List<String> lignes =
        jdbc.queryForList(
            "SELECT two_factor_secret FROM users WHERE id = :id",
            Map.of("id", userId),
            String.class);
    if (lignes.isEmpty()) {
      return null;
    }
    final String brut = lignes.get(0);
    try {
      return cipher.dechiffrer(brut);
    } catch (RuntimeException e) {
      LOG.warn("Secret TOTP non chiffré détecté pour l'utilisateur {} (legacy)", userId);
      return brut;
    }
  }

  /**
   * Retourne le nombre d'échecs TOTP consécutifs de l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return nombre d'échecs, ou 0 si l'utilisateur n'existe pas
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  int lireEchecsTotp(final String userId) {
    final Integer echecs =
        jdbc.queryForObject(
            "SELECT totp_failure_count FROM users WHERE id = :id",
            Map.of("id", userId),
            Integer.class);
    return echecs == null ? 0 : echecs;
  }

  /**
   * Désactive le TOTP (efface le secret).
   *
   * @param userId identifiant de l'utilisateur
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  void desactiverTotp(final String userId) {
    jdbc.update(
        "UPDATE users SET two_factor_secret = NULL, two_factor_enabled = false WHERE id = :id",
        Map.of("id", userId));
  }

  /**
   * Retourne le nombre total d'administrateurs sur l'instance.
   *
   * @return nombre d'utilisateurs ayant le rôle {@code admin}
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  int compterAdmins() {
    final Integer nombre =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin'", Map.of(), Integer.class);
    return nombre == null ? 0 : nombre;
  }

  /**
   * Supprime un utilisateur (cascade sur toutes ses données).
   *
   * @param userId identifiant de l'utilisateur à supprimer
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  void supprimer(final String userId) {
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
  }

  /**
   * Compte les concerts non vus/masqués pour un artiste suivi
   * (badge "nouveau" de navigation).
   * @param userId identifiant de l'utilisateur
   * @return nombre de concerts non vus pour les artistes suivis
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  int compterNouveauxMatchesArtistes(final String userId) {
    final Integer count =
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
                  AND (c.artiste_id = ua.artiste_id OR EXISTS (SELECT 1 FROM evenement_artistes ea WHERE ea.evenement_id = c.id AND ea.artiste_id = ua.artiste_id))
              )
            """,
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    return count != null ? count : 0;
  }

  /**
   * Données email lues depuis la base (utilisées pour construire
   * {@link ProfilUtilisateur} ).
   */
  /* package-private : accès SQL réservé à ParametresService/UserController
   * (même package). */
  record EmailsUtilisateur(String email, String pendingEmail) {}
}
