/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.RetrySerialisable;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.GenerateurSecrets;
import fr.strapontin.web.securite.VerificationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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

  private static final Logger log = LoggerFactory.getLogger(AdminService.class);
  private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Set<String> ROLES_VALIDES = Set.of("user", "admin");
  private static final int MAX_TENTATIVES_REVOCATION = 3;
  private static final int TAILLE_PAGE_UTILISATEURS = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final VerificationRepository verificationRepository;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private AdminService self;

  /**
   * @param jdbc accès JDBC
   * @param emailService service d'envoi d'emails
   * @param logAdminService service de logs admin
   * @param verificationRepository accès aux tokens de vérification
   */
  public AdminService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailService emailService,
      final LogAdminService logAdminService,
      final VerificationRepository verificationRepository) {
    this.jdbc = jdbc;
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.verificationRepository = verificationRepository;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence proxifiée de ce bean, pour
   * éviter le problème d'auto-référence à la construction — l'appel à {@link
   * #initierRevocationAdminTransactionnel} passe ainsi par le proxy AOP Spring et bénéficie de
   * l'isolation {@code SERIALIZABLE}. En dehors d'un contexte Spring (tests unitaires), {@code
   * self} peut être positionné manuellement sur l'instance elle-même.
   *
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final AdminService self) {
    this.self = self;
  }

  /**
   * Retourne une page d'utilisateurs avec leur dernière activité et compteurs de suivi.
   *
   * @param page numéro de page (0-indexé)
   * @return page d'utilisateurs triés par date de création
   */
  public ReponseUtilisateursAdmin listerUtilisateurs(final int page) {
    final int pageNum = Math.max(0, page);
    final Map<String, Object> paramsPage =
        Map.of("taille", TAILLE_PAGE_UTILISATEURS, "offset", pageNum * TAILLE_PAGE_UTILISATEURS);
    final List<Map<String, Object>> pageUsers =
        jdbc.queryForList(
            """
                SELECT u.id, u.email, u.role::text, u.username, u.display_username,
                       u.must_change_credentials, u.created_at, u.revocation_admin_le,
                       COUNT(DISTINCT us.salle_id) AS nb_salles,
                       COUNT(DISTINCT ua.artiste_id) AS nb_artistes,
                       COUNT(*) OVER() AS total_filtre
                FROM users u
                LEFT JOIN user_salles us ON us.user_id = u.id
                LEFT JOIN user_artistes ua ON ua.user_id = u.id
                GROUP BY u.id, u.email, u.role, u.username, u.display_username,
                         u.must_change_credentials, u.created_at, u.revocation_admin_le
                ORDER BY u.created_at
                LIMIT :taille OFFSET :offset
                """,
            paramsPage);

    final long total =
        pageUsers.isEmpty()
            ? compterUtilisateurs(pageNum)
            : ((Number) pageUsers.get(0).get("total_filtre")).longValue();

    final Map<String, Long> activiteParUsername = new HashMap<>();
    jdbc.query(
        """
        SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) as derniere
        FROM SPRING_SESSION
        WHERE PRINCIPAL_NAME IS NOT NULL
        GROUP BY PRINCIPAL_NAME
        """,
        Map.of(),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> activiteParUsername.put(rs.getString("PRINCIPAL_NAME"), rs.getLong("derniere")));

    final List<UtilisateurAdmin> utilisateurs =
        pageUsers.stream()
            .map(
                u -> {
                  final String username = (String) u.get("username");
                  final Long derniereMs = activiteParUsername.get(username);
                  final java.sql.Timestamp revocationTs =
                      (java.sql.Timestamp) u.get("revocation_admin_le");
                  final Number nbSallesNum = (Number) u.get("nb_salles");
                  final Number nbArtistesNum = (Number) u.get("nb_artistes");
                  return new UtilisateurAdmin(
                      (String) u.get("id"),
                      (String) u.get("email"),
                      (String) u.get("role"),
                      username,
                      (String) u.get("display_username"),
                      (Boolean) u.get("must_change_credentials"),
                      ((java.sql.Timestamp) u.get("created_at")).toInstant(),
                      derniereMs != null ? Instant.ofEpochMilli(derniereMs) : null,
                      revocationTs != null ? revocationTs.toInstant() : null,
                      nbSallesNum != null ? nbSallesNum.intValue() : 0,
                      nbArtistesNum != null ? nbArtistesNum.intValue() : 0);
                })
            .toList();

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_UTILISATEURS));
    return new ReponseUtilisateursAdmin(utilisateurs, pageNum, totalPages, total);
  }

  // page vide (offset au-delà du total, ex. dernier utilisateur supprimé depuis) : total_filtre
  // n'est renvoyé par aucune ligne, un second aller-retour reste alors nécessaire.
  private long compterUtilisateurs(final int pageNum) {
    if (pageNum == 0) return 0;
    final Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Long.class);
    return total != null ? total : 0;
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
  public Optional<ResultatCreation> creerUtilisateur(final String email, final String adminId, final String baseUrl) {
    final boolean emailExistant =
        !jdbc.queryForList(
            "SELECT 1 FROM users WHERE email = :email", Map.of("email", email), Integer.class)
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
    paramsUser.put("email", email);
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
        Map.of("id", accountId, "accountId", userId, "userId", userId, "password", hashMdp));

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
        "info", "admin", "compte créé", Map.of("email", email, "parAdmin", adminId));

    return Optional.of(new ResultatCreation(email, usernameTemp, lienReset));
  }

  /**
   * Valide et met à jour les champs d'un utilisateur.
   *
   * <p>Si le nouveau rôle est {@code admin}, envoie un email de promotion à l'utilisateur.
   *
   * <p>Si le nouveau rôle est {@code user} et que la cible est actuellement {@code admin}, la
   * rétrogradation n'est <strong>pas</strong> appliquée immédiatement ici : elle est déléguée à
   * {@link #initierRevocationAdmin}, qui applique le même délai différé de 7 jours et la même
   * garde « dernier admin » que le bouton dédié — sans quoi ce chemin {@code PATCH} permettait de
   * contourner intégralement les deux protections.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param requete champs à modifier (nom, email, role — tous optionnels)
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si les données sont invalides, l'email est déjà utilisé, ou
   *   (rétrogradation d'un admin) si c'est le seul admin restant (409)
   */
  @Transactional
  public void modifierUtilisateur(
      final String userId, final RequeteModifierUtilisateur requete, final String adminId) {
    final ChampsModification champs = validerEtNormaliser(requete, userId);
    final boolean retrogradationAdmin = detecterRetrogradationAdmin(userId, champs.role());
    // initierRevocationAdmin délègue à initierRevocationAdminTransactionnel
    // (REQUIRES_NEW + SERIALIZABLE) — appelée depuis cette méthode déjà @Transactional, elle
    // consommerait une seconde connexion HikariCP suspendue. Rejeter la combinaison en un seul
    // PATCH force l'appelant à séquencer deux requêtes distinctes (déjà fait côté front depuis
    // l'incident v2.6.4), plutôt que de rendre initierRevocationAdminTransactionnel sûr en
    // imbrication : NESTED perdrait l'isolation SERIALIZABLE nécessaire à la garde anti-concurrence
    // la transaction imbriquée héritant de l'isolation de la transaction englobante.
    if (retrogradationAdmin && (!champs.nom().isEmpty() || !champs.email().isEmpty())) {
      throw new ErreurMetier(
          "Impossible de combiner une rétrogradation admin avec une modification de nom/email en"
              + " une seule requête. Envoyer deux requêtes distinctes.");
    }

    appliquerModification(userId, champs, retrogradationAdmin);
    notifierModification(userId, adminId, champs, retrogradationAdmin);
  }

  private record ChampsModification(String nom, String email, String role) {}

  private ChampsModification validerEtNormaliser(
      final RequeteModifierUtilisateur requete, final String userId) {
    final String nom = requete.nom() == null ? "" : requete.nom().strip();
    final String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();
    final String role = requete.role() == null ? "" : requete.role().strip().toLowerCase();

    if (nom.isEmpty() && email.isEmpty() && role.isEmpty()) {
      throw new ErreurMetier("Au moins un champ à modifier est requis.");
    }
    if (!email.isEmpty() && !FORMAT_EMAIL.matcher(email).matches()) {
      throw new ErreurMetier("Format d'email invalide.");
    }
    if (!role.isEmpty() && !ROLES_VALIDES.contains(role)) {
      throw new ErreurMetier("Rôle invalide. Valeurs acceptées : user, admin.");
    }
    if (!email.isEmpty() && emailDejaPris(email, userId)) {
      throw new ErreurMetier("Cette adresse email est déjà utilisée.");
    }
    return new ChampsModification(nom, email, role);
  }

  private boolean emailDejaPris(final String email, final String userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE email = :email AND id != :id",
            Map.of("email", email, "id", userId),
            Integer.class)
        .isEmpty();
  }

  private boolean detecterRetrogradationAdmin(final String userId, final String role) {
    if (role.isEmpty()) return false;
    final List<Map<String, Object>> roleActuelRows =
        jdbc.queryForList(
            "SELECT role::text AS role FROM users WHERE id = :id", Map.of("id", userId));
    final String roleActuel =
        roleActuelRows.isEmpty() ? null : (String) roleActuelRows.get(0).get("role");
    return "user".equals(role) && "admin".equals(roleActuel);
  }

  private void appliquerModification(
      final String userId, final ChampsModification champs, final boolean retrogradationAdmin) {
    final Map<String, Object> miseAJour = new HashMap<>();
    miseAJour.put("updatedAt", Timestamp.from(Instant.now()));
    miseAJour.put("userId", userId);
    if (!champs.nom().isEmpty()) miseAJour.put("nom", champs.nom());
    if (!champs.email().isEmpty()) miseAJour.put("email", champs.email());
    if (!champs.role().isEmpty() && !retrogradationAdmin) miseAJour.put("role", champs.role());

    final StringBuilder setClauses = new StringBuilder("updated_at = :updatedAt");
    if (!champs.nom().isEmpty()) setClauses.append(", display_username = :nom");
    if (!champs.email().isEmpty()) setClauses.append(", email = :email");
    if (!champs.role().isEmpty() && !retrogradationAdmin) {
      setClauses.append(", role = CAST(:role AS role_utilisateur)");
    }
    jdbc.update("UPDATE users SET " + setClauses + " WHERE id = :userId", miseAJour);
  }

  private void notifierModification(
      final String userId, final String adminId, final ChampsModification champs, final boolean retrogradationAdmin) {
    if (!champs.nom().isEmpty()) {
      logAdminService.logAdmin(
          "info", "admin", "nom modifié", Map.of("userId", userId, "parAdmin", adminId));
    }
    if (!champs.email().isEmpty()) {
      logAdminService.logAdmin(
          "warn",
          "admin",
          "email modifié",
          Map.of("userId", userId, "nouvelEmail", champs.email(), "parAdmin", adminId));
    }
    if (champs.role().isEmpty()) return;
    if (retrogradationAdmin) {
      initierRevocationAdmin(userId, adminId);
      return;
    }
    logAdminService.logAdmin(
        "warn",
        "admin",
        "rôle modifié",
        Map.of("userId", userId, "nouveauRole", champs.role(), "parAdmin", adminId));
    if ("admin".equals(champs.role())) {
      final List<Map<String, Object>> cible =
          jdbc.queryForList("SELECT email FROM users WHERE id = :id", Map.of("id", userId));
      if (!cible.isEmpty()) {
        emailService.envoyerPromotionAdmin((String) cible.get(0).get("email"));
      }
    }
  }

  /**
   * Initie une révocation différée du rôle admin (effective dans 7 jours).
   *
   * <p>Réessaie jusqu'à {@value MAX_TENTATIVES_REVOCATION} fois en cas d'échec de sérialisation
   * (deux révocations concurrentes détectées par PostgreSQL) — sans cela, deux admins se révoquant
   * mutuellement au même instant pourraient tous deux passer la garde « dernier admin » avant
   * qu'aucun des deux {@code UPDATE} ne soit committé.
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si l'utilisateur est le seul admin restant (409) ou si l'utilisateur
   *   n'est pas admin (400)
   */
  public void initierRevocationAdmin(final String userId, final String adminId) {
    RetrySerialisable.executer(
        MAX_TENTATIVES_REVOCATION,
        log,
        "la révocation admin",
        () -> self.initierRevocationAdminTransactionnel(userId, adminId));
  }

  /**
   * Partie transactionnelle et isolée de {@link #initierRevocationAdmin}.
   *
   * <p>Envoie un email de préavis à l'administrateur concerné et loggue l'action. {@code
   * REQUIRES_NEW} garantit une transaction dédiée même si appelée depuis une transaction déjà
   * ouverte (ex. {@link #modifierUtilisateur}) — sans quoi l'isolation SERIALIZABLE serait ignorée
   * en cas d'imbrication dans une transaction moins stricte.
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si l'utilisateur est le seul admin restant (409) ou si l'utilisateur
   *   n'est pas admin (400)
   */
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  void initierRevocationAdminTransactionnel(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email, role::text AS role FROM users WHERE id = :id", Map.of("id", userId));
    if (cible.isEmpty()) throw new ErreurMetier("Utilisateur introuvable.", 404);
    if (!"admin".equals(cible.get(0).get("role"))) {
      throw new ErreurMetier("Cet utilisateur n'est pas administrateur.");
    }

    final int nbAdmins =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin' AND revocation_admin_le IS NULL",
            Map.of(),
            Integer.class);
    if (nbAdmins <= 1) {
      throw new ErreurMetier("Impossible de révoquer le seul administrateur restant.", 409);
    }

    final Instant dateEffective = Instant.now().plus(7, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE users SET revocation_admin_le = :date WHERE id = :id",
        Map.of("date", Timestamp.from(dateEffective), "id", userId));

    final String dateFormatee =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(Locale.FRANCE)
            .format(LocalDate.ofInstant(dateEffective, ZoneId.of("Europe/Paris")));

    emailService.envoyerPreavisRevocationAdmin((String) cible.get(0).get("email"), dateFormatee);
    logAdminService.logAdmin(
        "warn",
        "admin",
        "révocation admin initiée",
        Map.of("userId", userId, "dateEffective", dateFormatee, "parAdmin", adminId));
  }

  /**
   * Annule une révocation admin en cours (remet revocation_admin_le à NULL).
   *
   * @param userId identifiant de l'administrateur concerné
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si l'utilisateur n'existe pas ou n'a pas de révocation en cours
   */
  public void annulerRevocationAdmin(final String userId, final String adminId) {
    final int miseAJour =
        jdbc.update(
            "UPDATE users SET revocation_admin_le = NULL WHERE id = :id"
                + " AND revocation_admin_le IS NOT NULL",
            Map.of("id", userId));
    if (miseAJour == 0) {
      throw new ErreurMetier("Aucune révocation en cours pour cet utilisateur.");
    }
    logAdminService.logAdmin(
        "info", "admin", "révocation admin annulée", Map.of("userId", userId, "parAdmin", adminId));
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
        jdbc.queryForList("SELECT email FROM users WHERE id = :userId", Map.of("userId", userId));
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of("userId", userId));
    logAdminService.logAdmin(
        "warn", "admin", "compte supprimé", Map.of("userId", userId, "parAdmin", adminId));
    if (!cible.isEmpty()) {
      emailService.envoyerSuppressionCompte((String) cible.get(0).get("email"));
    }
  }

  /**
   * Envoie un email de réinitialisation de mot de passe à un utilisateur.
   *
   * <p>Le lien de réinitialisation est toujours retourné dans {@link ResultatReset#lienReset()}
   * comme {@link #creerUtilisateur}, c'est au contrôleur de décider de l'exposer à l'admin selon
   * la disponibilité de Resend, pour garder un filet de secours si l'email n'atteint jamais
   * l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param adminId identifiant de l'admin demandeur
   * @param baseUrl URL de base pour construire le lien
   * @return le résultat (email + lien) si l'utilisateur existe, {@link Optional#empty()} sinon
   */
  @Transactional
  public Optional<ResultatReset> reinitialiserMdp(final String userId, final String adminId, final String baseUrl) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT id, email FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) return Optional.empty();

    final String email = (String) cible.get(0).get("email");
    final String prefixeReset = "reset:" + email;
    verificationRepository.supprimerParIdentifiant(prefixeReset);

    final String token = GenerateurSecrets.genererToken(32);
    final Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(), prefixeReset, GenerateurSecrets.sha256Hex(token), expiresAt);

    final String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerResetMotDePasse(email, lienReset);
    logAdminService.logAdmin(
        "warn",
        "admin",
        "mot de passe réinitialisé",
        Map.of("userId", userId, "parAdmin", adminId));

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
        jdbc.queryForList("SELECT email FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) return false;

    jdbc.update(
        "UPDATE users SET two_factor_secret = NULL, two_factor_enabled = false,"
            + " totp_failure_count = 0 WHERE id = :id",
        Map.of("id", userId));
    emailService.envoyer2faReinitialisee((String) cible.get(0).get("email"));
    logAdminService.logAdmin(
        "warn", "admin", "TOTP réinitialisé", Map.of("userId", userId, "parAdmin", adminId));
    return true;
  }

  /**
   * Révoque immédiatement toutes les sessions actives d'un utilisateur (ex. compte compromis,
   * départ, demande explicite de l'utilisateur) — indépendant de la révocation admin ({@link
   * #initierRevocationAdmin}), qui ne concerne que la rétrogradation d'un rôle.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param adminId identifiant de l'admin à l'origine de l'action
   * @return {@code true} si l'utilisateur existait, {@code false} sinon
   */
  public boolean revoquerToutesSessions(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT username FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) return false;

    final int supprimees =
        jdbc.update(
            "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = :username",
            Map.of("username", cible.get(0).get("username")));
    logAdminService.logAdmin(
        "warn",
        "admin",
        "sessions révoquées",
        Map.of("userId", userId, "parAdmin", adminId, "sessionsSupprimees", supprimees));
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
