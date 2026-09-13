/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.securite.GenerateurSecrets;
import fr.strapontin.web.securite.VerificationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service d'administration : gestion des comptes utilisateurs.
 *
 * <p>Contient la logique métier et l'accès aux données pour la gestion des utilisateurs. {@link
 * AdminController#creerUtilisateur} est atomique ({@code @Transactional}). La gestion des clés
 * VAPID est déléguée à {@link VapidService}, la configuration applicative à {@link
 * ConfigAdminService} et les statistiques de jobs à {@link JobsAdminService}.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class AdminService {

  private static final String SOURCE_ADMIN = "admin";
  private static final String CLE_EMAIL = "email";
  private static final String CLE_USER_ID = "userId";
  private static final String CLE_PAR_ADMIN = "parAdmin";
  private static final String NIVEAU_WARN = "warn";

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailService emailService;
  private final EmailServiceAdmin emailServiceAdmin;
  private final AdminUtilisateursListingService adminUtilisateursListingService;
  private final AdminModificationUtilisateurService adminModificationUtilisateurService;
  private final LogAdminService logAdminService;
  private final VerificationRepository verificationRepository;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

  /**
   * @param jdbc accès JDBC
   * @param emailService service d'envoi d'emails
   * @param emailServiceAdmin emails déclenchés par une action d'administration
   * @param adminUtilisateursListingService pagination des utilisateurs
   * @param adminModificationUtilisateurService modification nom/email/rôle d'un utilisateur
   * @param logAdminService service de logs admin
   * @param verificationRepository accès aux tokens de vérification
   */
  public AdminService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailService emailService,
      final EmailServiceAdmin emailServiceAdmin,
      final AdminUtilisateursListingService adminUtilisateursListingService,
      final AdminModificationUtilisateurService adminModificationUtilisateurService,
      final LogAdminService logAdminService,
      final VerificationRepository verificationRepository) {
    this.jdbc = jdbc;
    this.emailService = emailService;
    this.emailServiceAdmin = emailServiceAdmin;
    this.adminUtilisateursListingService = adminUtilisateursListingService;
    this.adminModificationUtilisateurService = adminModificationUtilisateurService;
    this.logAdminService = logAdminService;
    this.verificationRepository = verificationRepository;
  }

  /**
   * Retourne une page d'utilisateurs avec leur dernière activité et compteurs
   * de suivi.
   *
   * @param page numéro de page (0-indexé)
   * @return page d'utilisateurs triés par date de création
   */
  public ReponseUtilisateursAdmin listerUtilisateurs(final int page) {
    return adminUtilisateursListingService.lister(page);
  }

  /**
   * Crée un compte utilisateur et envoie un email d'invitation (atomique).
   *
   * @param email email du nouvel utilisateur
   * @param adminId identifiant de l'admin demandeur
   * @param baseUrl URL de base pour construire le lien d'invitation
   * @return résultat de la création, ou {@link Optional#empty()} si l'email est déjà pris
   */
  @Transactional
  public Optional<ResultatCreation> creerUtilisateur(
      final String email, final String adminId, final String baseUrl) {
    final boolean emailExistant =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE email = :email", Map.of(CLE_EMAIL, email), Integer.class)
            .isEmpty();
    if (emailExistant) {
      return Optional.empty();
    }

    final String userId = UUID.randomUUID().toString();
    final String usernameTemp = "u_" + GenerateurSecrets.genererHex(4);
    final String motDePasseTemp = GenerateurSecrets.genererToken(12);
    final String hashMdp = bcrypt.encode(motDePasseTemp);

    final Map<String, Object> paramsUser = new HashMap<>();
    paramsUser.put("id", userId);
    paramsUser.put(CLE_EMAIL, email);
    paramsUser.put("username", usernameTemp);
    paramsUser.put("displayUsername", usernameTemp);
    jdbc.update(
        """
        INSERT INTO users (id, email, email_verified, role, username, display_username,
                           must_change_credentials, created_at, updated_at)
        VALUES (:id, :email, false, 'user', :username, :displayUsername,
                true, now(), now())
        """,
        paramsUser);

    final String accountId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
        VALUES (:id, :accountId, 'credential', :userId, :password, now(), now())
        """,
        Map.of("id", accountId, "accountId", userId, CLE_USER_ID, userId, "password", hashMdp));

    final String token = GenerateurSecrets.genererToken(32);
    final Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(),
        "invite:" + email,
        GenerateurSecrets.sha256Hex(token),
        expiresAt);

    final String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerInvitation(email, lienReset);
    logAdminService.logAdmin(
        "info", SOURCE_ADMIN, "compte créé", Map.of(CLE_EMAIL, email, CLE_PAR_ADMIN, adminId));

    return Optional.of(new ResultatCreation(email, usernameTemp, lienReset));
  }

  /**
   * Valide et met à jour les champs d'un utilisateur.
   *
   * <p>Si le nouveau rôle est {@code admin}, envoie un email de promotion à l'utilisateur.
   *
   * <p>Si le nouveau rôle est {@code user} et que la cible est actuellement {@code admin}, la
   * rétrogradation n'est <strong>pas</strong> appliquée immédiatement ici : elle est déléguée à
   * {@link AdminRevocationService#initierRevocationAdmin}, qui applique le même délai différé de 7
   * jours et la même garde « dernier admin » que le bouton dédié — sans quoi ce chemin {@code
   * PATCH} permettait de contourner intégralement les deux protections.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param requete champs à modifier (nom, email, role — tous optionnels)
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si les données sont invalides, l'email est déjà utilisé, ou
   *     (rétrogradation d'un admin) si c'est le seul admin restant (409)
   */
  public void modifierUtilisateur(
      final String userId, final RequeteModifierUtilisateur requete, final String adminId) {
    adminModificationUtilisateurService.modifierUtilisateur(userId, requete, adminId);
  }

  /**
   * Supprime un compte utilisateur et notifie l'utilisateur par email.
   *
   * @param userId identifiant de l'utilisateur à supprimer
   * @param adminId identifiant de l'admin demandeur
   */
  @Transactional
  public void supprimerUtilisateur(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
    logAdminService.logAdmin(
        NIVEAU_WARN,
        SOURCE_ADMIN,
        "compte supprimé",
        Map.of(CLE_USER_ID, userId, CLE_PAR_ADMIN, adminId));
    if (!cible.isEmpty()) {
      emailServiceAdmin.envoyerSuppressionCompte((String) cible.get(0).get(CLE_EMAIL));
    }
  }

  /**
   * Envoie un email de réinitialisation de mot de passe à un utilisateur.
   *
   * <p>Le lien de réinitialisation est toujours retourné dans {@link ResultatReset#lienReset()}
   * comme {@link #creerUtilisateur}, c'est au contrôleur de décider de l'exposer à l'admin selon la
   * disponibilité de Resend, pour garder un filet de secours si l'email n'atteint jamais
   * l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param adminId identifiant de l'admin demandeur
   * @param baseUrl URL de base pour construire le lien
   * @return le résultat (email + lien) si l'utilisateur existe, {@link Optional#empty()} sinon
   */
  @Transactional
  public Optional<ResultatReset> reinitialiserMdp(
      final String userId, final String adminId, final String baseUrl) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT id, email FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
    if (cible.isEmpty()) {
      return Optional.empty();
    }

    final String email = (String) cible.get(0).get(CLE_EMAIL);
    final String prefixeReset = "reset:" + email;
    verificationRepository.supprimerParIdentifiant(prefixeReset);

    final String token = GenerateurSecrets.genererToken(32);
    final Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(), prefixeReset, GenerateurSecrets.sha256Hex(token), expiresAt);

    final String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerResetMotDePasse(email, lienReset);
    logAdminService.logAdmin(
        NIVEAU_WARN,
        SOURCE_ADMIN,
        "mot de passe réinitialisé",
        Map.of(CLE_USER_ID, userId, CLE_PAR_ADMIN, adminId));

    return Optional.of(new ResultatReset(email, lienReset));
  }

  /**
   * Réinitialise la 2FA TOTP d'un utilisateur : efface le secret et désactive la 2FA.
   *
   * <p>Utilisé quand un utilisateur perd l'accès à son application d'authentification (ou que son
   * secret est désynchronisé) : il pourra se reconnecter sans code, puis ré-enrôler son TOTP.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param adminId identifiant de l'admin à l'origine de l'action
   * @return {@code true} si l'utilisateur existait, {@code false} sinon
   */
  public boolean reinitialiserTotp(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
    if (cible.isEmpty()) {
      return false;
    }

    jdbc.update(
        "UPDATE users SET two_factor_secret = NULL, two_factor_enabled = false,"
            + " totp_failure_count = 0 WHERE id = :id",
        Map.of("id", userId));
    emailService.envoyer2faReinitialisee((String) cible.get(0).get(CLE_EMAIL));
    logAdminService.logAdmin(
        NIVEAU_WARN,
        SOURCE_ADMIN,
        "TOTP réinitialisé",
        Map.of(CLE_USER_ID, userId, CLE_PAR_ADMIN, adminId));
    return true;
  }

  /**
   * Révoque immédiatement toutes les sessions actives d'un utilisateur (ex. compte compromis,
   * départ, demande explicite de l'utilisateur) — indépendant de la révocation admin ({@link
   * AdminRevocationService#initierRevocationAdmin}), qui ne concerne que la rétrogradation d'un
   * rôle.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param adminId identifiant de l'admin à l'origine de l'action
   * @return {@code true} si l'utilisateur existait, {@code false} sinon
   */
  public boolean revoquerToutesSessions(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT username FROM users WHERE id = :userId", Map.of(CLE_USER_ID, userId));
    if (cible.isEmpty()) {
      return false;
    }

    final int supprimees =
        jdbc.update(
            "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = :username",
            Map.of("username", cible.get(0).get("username")));
    logAdminService.logAdmin(
        NIVEAU_WARN,
        SOURCE_ADMIN,
        "sessions révoquées",
        Map.of(CLE_USER_ID, userId, CLE_PAR_ADMIN, adminId, "sessionsSupprimees", supprimees));
    return true;
  }

  /**
   * Retourne les N derniers logs admin.
   *
   * @return liste des entrées de log
   */
  public List<LogAdminService.EntreeLogAdmin> lireLogsAdmin() {
    return logAdminService.lireLogs(200);
  }
}
