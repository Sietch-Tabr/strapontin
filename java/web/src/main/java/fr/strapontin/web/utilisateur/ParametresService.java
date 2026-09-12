/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.GenerateurSecrets;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service pour les paramètres utilisateur : statistiques et flux de changement d'email.
 *
 * <p>Regroupe la logique métier extraite de {@code ParametresRestController} : accès JDBC,
 * génération de tokens et envoi d'emails de confirmation.
 *
 * @since 2.5.4
 * @author Kwaite
 */
@Service
public class ParametresService {

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailService emailService;
  private final UtilisateurRepository utilisateurRepository;

  /**
   * @param jdbc accès JDBC
   * @param emailService service d'envoi d'emails
   * @param utilisateurRepository accès aux données utilisateur
   */
  public ParametresService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailService emailService,
      final UtilisateurRepository utilisateurRepository) {
    this.jdbc = jdbc;
    this.emailService = emailService;
    this.utilisateurRepository = utilisateurRepository;
  }

  /**
   * Compte les concerts non vus/masqués pour un artiste suivi (badge "nouveau" de navigation).
   *
   * @param userId identifiant de l'utilisateur
   * @return nombre de concerts non vus pour les artistes suivis
   */
  public int compterNouveauxMatchesArtistes(final String userId) {
    return utilisateurRepository.compterNouveauxMatchesArtistes(userId);
  }

  /**
   * Retourne le nombre d'artistes et de salles suivis par l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return map avec les clés {@code nbArtistes} et {@code nbSalles}
   */
  public Map<String, Object> stats(final String userId) {
    final Integer nbArtistes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);
    final Integer nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);
    return Map.of(
        "nbArtistes", nbArtistes != null ? nbArtistes : 0,
        "nbSalles", nbSalles != null ? nbSalles : 0);
  }

  /**
   * Vérifie si une adresse email est déjà utilisée par un autre compte.
   *
   * @param email email à vérifier (normalisé)
   * @param userId identifiant de l'utilisateur courant (exclu de la vérification)
   * @return {@code true} si l'email est déjà pris
   */
  public boolean emailDejaUtilise(final String email, final String userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE email = :email AND id != :id",
            Map.of("email", email, "id", userId),
            Integer.class)
        .isEmpty();
  }

  /**
   * Indique si l'envoi d'email est disponible (clé Resend configurée).
   *
   * @return {@code true} si le service email est opérationnel
   */
  public boolean emailDisponible() {
    return emailService.estDisponible();
  }

  /**
   * Déclenche le flux de confirmation de changement d'email.
   *
   * <p>Génère un token sécurisé, l'insère dans {@code verifications}, met à jour {@code
   * pending_email} et envoie le lien de confirmation.
   *
   * @param details utilisateur courant
   * @param emailNorm nouvelle adresse email normalisée
   * @param baseUrl URL de base de l'application (pour construire le lien de confirmation)
   */
  @Transactional
  public void demanderChangementEmail(
      final UtilisateurDetails details, final String emailNorm, final String baseUrl) {
    final String token = GenerateurSecrets.genererHex(32);
    final String prefixe = "email-change:" + details.id();
    jdbc.update("DELETE FROM verifications WHERE identifier = :p", Map.of("p", prefixe));
    jdbc.update(
        """
        INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
        VALUES (:id, :identifier, :value, :expiresAt, now(), now())
        """,
        Map.of(
            "id", UUID.randomUUID().toString(),
            "identifier", prefixe,
            "value", GenerateurSecrets.sha256Hex(token),
            "expiresAt", Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))));
    jdbc.update(
        "UPDATE users SET pending_email = :email WHERE id = :id",
        Map.of("email", emailNorm, "id", details.id()));
    final String lien = baseUrl + "/api/parametres/confirmer-email?token=" + token;
    emailService.envoyerConfirmationEmail(emailNorm, lien);
  }

  /**
   * Résultat de la confirmation d'email.
   *
   * @param statut code décrivant l'issue de la confirmation
   * @param userId identifiant de l'utilisateur ciblé (peut être nul si le token est invalide)
   */
  public record ResultatConfirmation(Statut statut, String userId) {
    /** Codes de résultat possibles pour la confirmation d'email. */
    public enum Statut {
      /** Confirmation réussie. */
      OK,
      /** Token absent, vide ou non trouvé en base. */
      LIEN_INVALIDE,
      /** Token expiré (non trouvé car expires_at dépassé). */
      LIEN_EXPIRE,
      /** L'utilisateur du token diffère de l'utilisateur authentifié. */
      MAUVAIS_UTILISATEUR,
      /** Aucun {@code pending_email} pour cet utilisateur. */
      AUCUN_CHANGEMENT,
      /** Le nouvel email est déjà utilisé par un autre compte. */
      EMAIL_DEJA_UTILISE,
    }
  }

  /**
   * Valide le token et applique le changement d'email si tout est correct.
   *
   * @param token token de confirmation (hexadécimal brut)
   * @param userIdAuthentifie identifiant de l'utilisateur authentifié
   * @return résultat indiquant le statut et l'identifiant utilisateur cible
   */
  private record TokenResolu(ResultatConfirmation.Statut echec, String userId) {}

  private TokenResolu resoudreUserIdDepuisToken(
      final String token, final String userIdAuthentifie) {
    if (token == null || token.isBlank()) {
      return new TokenResolu(ResultatConfirmation.Statut.LIEN_INVALIDE, null);
    }

    final List<Map<String, Object>> verifs =
        jdbc.queryForList(
            """
            SELECT identifier FROM verifications
            WHERE value = :hash AND expires_at > NOW()
            """,
            Map.of("hash", GenerateurSecrets.sha256Hex(token)));

    if (verifs.isEmpty()) {
      return new TokenResolu(ResultatConfirmation.Statut.LIEN_EXPIRE, null);
    }

    final String identifier = (String) verifs.get(0).get("identifier");
    final String userId = identifier.contains(":") ? identifier.split(":", 2)[1] : null;
    if (userId == null) {
      return new TokenResolu(ResultatConfirmation.Statut.LIEN_INVALIDE, null);
    }

    if (!userId.equals(userIdAuthentifie)) {
      return new TokenResolu(ResultatConfirmation.Statut.MAUVAIS_UTILISATEUR, userId);
    }
    return new TokenResolu(null, userId);
  }

  @Transactional
  public ResultatConfirmation confirmerEmail(final String token, final String userIdAuthentifie) {
    final TokenResolu resolu = resoudreUserIdDepuisToken(token, userIdAuthentifie);
    if (resolu.echec() != null) {
      return new ResultatConfirmation(resolu.echec(), resolu.userId());
    }
    final String userId = resolu.userId();

    final List<String> pendingEmails =
        jdbc.queryForList(
            "SELECT pending_email FROM users WHERE id = :id", Map.of("id", userId), String.class);

    if (pendingEmails.isEmpty() || pendingEmails.get(0) == null) {
      return new ResultatConfirmation(ResultatConfirmation.Statut.AUCUN_CHANGEMENT, userId);
    }
    final String nouvelEmail = pendingEmails.get(0);

    if (emailDejaUtilise(nouvelEmail, userId)) {
      jdbc.update("UPDATE users SET pending_email = NULL WHERE id = :id", Map.of("id", userId));
      jdbc.update(
          "DELETE FROM verifications WHERE identifier = :p", Map.of("p", "email-change:" + userId));
      return new ResultatConfirmation(ResultatConfirmation.Statut.EMAIL_DEJA_UTILISE, userId);
    }

    jdbc.update(
        "UPDATE users SET email = :email, pending_email = NULL, updated_at = now() WHERE id = :id",
        Map.of("email", nouvelEmail, "id", userId));
    jdbc.update(
        "DELETE FROM verifications WHERE identifier = :p", Map.of("p", "email-change:" + userId));

    return new ResultatConfirmation(ResultatConfirmation.Statut.OK, userId);
  }
}
