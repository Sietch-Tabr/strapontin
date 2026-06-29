/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
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
import java.util.ArrayList;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/// Service d'administration : gestion des utilisateurs, jobs et configuration applicative.
///
/// <p>Contient la logique métier et l'accès aux données pour le domaine admin.
/// {@link AdminController#creerUtilisateur} est atomique ({@code @Transactional}).
/// La gestion des clés VAPID est déléguée à {@link VapidService}.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class AdminService {

  private static final Logger log = LoggerFactory.getLogger(AdminService.class);
  private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final List<String> CLES_AUTORISEES =
      List.of(
          "TICKETMASTER_API_KEY",
          "RESEND_API_KEY",
          "RESEND_WEBHOOK_SECRET",
          "CONTACT_EMAIL",
          "PUBLIC_BASE_URL",
          "SIGNAL_LIEN",
          "VAPID_PUBLIC_KEY",
          "VAPID_PRIVATE_KEY");
  private static final Set<String> CLES_EN_CLAIR =
      Set.of("CONTACT_EMAIL", "PUBLIC_BASE_URL", "SIGNAL_LIEN");
  private static final Set<String> CLES_OBLIGATOIRES = Set.of("CONTACT_EMAIL", "PUBLIC_BASE_URL");
  private static final Pattern FORMAT_TM_KEY = Pattern.compile("^[A-Za-z0-9]{16,64}$");
  private static final Pattern FORMAT_RESEND_KEY = Pattern.compile("^re_[A-Za-z0-9_-]{10,}$");
  private static final Pattern FORMAT_WEBHOOK_SECRET =
      Pattern.compile("^whsec_[A-Za-z0-9+/=]{16,}$");
  private static final Set<String> ROLES_VALIDES = Set.of("user", "admin");

  private static final String TM_URL_TEST =
      "https://app.ticketmaster.com/discovery/v2/events.json"
          + "?apikey={cle}&size=1&countryCode=FR&classificationName=music";

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final ObjectMapper objectMapper;
  private final VerificationRepository verificationRepository;
  private final RestClient restClient;
  private final WorkerProxyService workerProxyService;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

  /// @param jdbc                   accès JDBC
  /// @param configService          lecture/écriture de la configuration applicative
  /// @param emailService           service d'envoi d'emails
  /// @param logAdminService        service de logs admin
  /// @param objectMapper           désérialiseur JSON (quota TM)
  /// @param verificationRepository accès aux tokens de vérification
  /// @param restClient             client HTTP pour les tests d'API externes
  /// @param workerProxyService     proxy HTTP vers le worker (jobs en cours, prochaines exécutions)
  public AdminService(
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      LogAdminService logAdminService,
      ObjectMapper objectMapper,
      VerificationRepository verificationRepository,
      RestClient restClient,
      WorkerProxyService workerProxyService) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.objectMapper = objectMapper;
    this.verificationRepository = verificationRepository;
    this.restClient = restClient;
    this.workerProxyService = workerProxyService;
  }

  /// Teste la validité de la clé Ticketmaster API en effectuant un appel minimal.
  ///
  /// @return {@code true} si la clé est configurée et acceptée par l'API TM (HTTP 200)
  public boolean testerApiTm() {
    Optional<String> cle = configService.lireConfig("TICKETMASTER_API_KEY");
    if (cle.isEmpty()) return false;
    try {
      restClient.get().uri(TM_URL_TEST, cle.get()).retrieve().toBodilessEntity();
      return true;
    } catch (Exception e) {
      log.warn("Test clé TM : réponse invalide — {}", e.getMessage());
      return false;
    }
  }

  /// Retourne la liste des utilisateurs avec leur dernière activité et compteurs de suivi.
  ///
  /// @return utilisateurs triés par date de création
  public List<UtilisateurAdmin> listerUtilisateurs() {
    List<Map<String, Object>> tousLesUsers =
        jdbc.queryForList(
            """
            SELECT u.id, u.email, u.role::text, u.username, u.display_username,
                   u.must_change_credentials, u.created_at, u.revocation_admin_le,
                   COUNT(DISTINCT us.salle_uuid) AS nb_salles,
                   COUNT(DISTINCT ua.artiste_id) AS nb_artistes
            FROM users u
            LEFT JOIN user_salles us ON us.user_id = u.id
            LEFT JOIN user_artistes ua ON ua.user_id = u.id
            GROUP BY u.id, u.email, u.role, u.username, u.display_username,
                     u.must_change_credentials, u.created_at, u.revocation_admin_le
            ORDER BY u.created_at
            """,
            Map.of());

    Map<String, Long> activiteParUsername = new HashMap<>();
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

    return tousLesUsers.stream()
        .map(
            u -> {
              String username = (String) u.get("username");
              Long derniereMs = activiteParUsername.get(username);
              java.sql.Timestamp revocationTs = (java.sql.Timestamp) u.get("revocation_admin_le");
              Number nbSallesNum = (Number) u.get("nb_salles");
              Number nbArtistesNum = (Number) u.get("nb_artistes");
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
  }

  /// Crée un compte utilisateur et envoie un email d'invitation (atomique).
  ///
  /// @param email    email du nouvel utilisateur
  /// @param adminId  identifiant de l'admin demandeur
  /// @param baseUrl  URL de base pour construire le lien d'invitation
  /// @return résultat de la création, ou {@link Optional#empty()} si l'email est déjà pris
  @Transactional
  public Optional<ResultatCreation> creerUtilisateur(String email, String adminId, String baseUrl) {
    boolean emailExistant =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE email = :email", Map.of("email", email), Integer.class)
            .isEmpty();
    if (emailExistant) {
      return Optional.empty();
    }

    String userId = UUID.randomUUID().toString();
    String usernameTemp = "u_" + GenerateurSecrets.genererHex(4);
    String motDePasseTemp = GenerateurSecrets.genererToken(12);
    String hashMdp = bcrypt.encode(motDePasseTemp);

    Map<String, Object> paramsUser = new HashMap<>();
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

    String accountId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
        VALUES (:id, :accountId, 'credential', :userId, :password, now(), now())
        """,
        Map.of("id", accountId, "accountId", userId, "userId", userId, "password", hashMdp));

    String token = GenerateurSecrets.genererToken(32);
    Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(),
        "invite:" + email,
        GenerateurSecrets.sha256Hex(token),
        expiresAt);

    String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerInvitation(email, lienReset);
    logAdminService.logAdmin(
        "info", "admin", "compte créé", Map.of("email", email, "parAdmin", adminId));

    return Optional.of(new ResultatCreation(email, usernameTemp, lienReset));
  }

  /// Valide et met à jour les champs d'un utilisateur.
  ///
  /// <p>Si le nouveau rôle est {@code admin}, envoie un email de promotion à l'utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur cible
  /// @param requete champs à modifier (nom, email, role — tous optionnels)
  /// @param adminId identifiant de l'admin demandeur
  /// @throws ErreurMetier si les données sont invalides ou l'email est déjà utilisé
  @Transactional
  public void modifierUtilisateur(
      String userId, RequeteModifierUtilisateur requete, String adminId) {
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();
    String role = requete.role() == null ? "" : requete.role().strip().toLowerCase();

    if (nom.isEmpty() && email.isEmpty() && role.isEmpty()) {
      throw new ErreurMetier("Au moins un champ à modifier est requis.");
    }
    if (!email.isEmpty() && !FORMAT_EMAIL.matcher(email).matches()) {
      throw new ErreurMetier("Format d'email invalide.");
    }
    if (!role.isEmpty() && !ROLES_VALIDES.contains(role)) {
      throw new ErreurMetier("Rôle invalide. Valeurs acceptées : user, admin.");
    }
    if (!email.isEmpty()) {
      boolean emailPris =
          !jdbc.queryForList(
                  "SELECT 1 FROM users WHERE email = :email AND id != :id",
                  Map.of("email", email, "id", userId),
                  Integer.class)
              .isEmpty();
      if (emailPris) throw new ErreurMetier("Cette adresse email est déjà utilisée.");
    }

    Map<String, Object> miseAJour = new HashMap<>();
    miseAJour.put("updatedAt", Timestamp.from(Instant.now()));
    miseAJour.put("userId", userId);
    if (!nom.isEmpty()) miseAJour.put("nom", nom);
    if (!email.isEmpty()) miseAJour.put("email", email);
    if (!role.isEmpty()) miseAJour.put("role", role);

    StringBuilder setClauses = new StringBuilder("updated_at = :updatedAt");
    if (!nom.isEmpty()) setClauses.append(", display_username = :nom");
    if (!email.isEmpty()) setClauses.append(", email = :email");
    if (!role.isEmpty()) setClauses.append(", role = CAST(:role AS role_utilisateur)");
    jdbc.update("UPDATE users SET " + setClauses + " WHERE id = :userId", miseAJour);

    if (!nom.isEmpty()) {
      logAdminService.logAdmin(
          "info", "admin", "nom modifié", Map.of("userId", userId, "parAdmin", adminId));
    }
    if (!email.isEmpty()) {
      logAdminService.logAdmin(
          "warn",
          "admin",
          "email modifié",
          Map.of("userId", userId, "nouvelEmail", email, "parAdmin", adminId));
    }
    if (!role.isEmpty()) {
      logAdminService.logAdmin(
          "warn",
          "admin",
          "rôle modifié",
          Map.of("userId", userId, "nouveauRole", role, "parAdmin", adminId));
      if ("admin".equals(role)) {
        List<Map<String, Object>> cible =
            jdbc.queryForList("SELECT email FROM users WHERE id = :id", Map.of("id", userId));
        if (!cible.isEmpty()) {
          emailService.envoyerPromotionAdmin((String) cible.get(0).get("email"));
        }
      }
    }
  }

  /// Initie une révocation différée du rôle admin (effective dans 7 jours).
  ///
  /// <p>Envoie un email de préavis à l'administrateur concerné et loggue l'action.
  ///
  /// @param userId  identifiant de l'administrateur à révoquer
  /// @param adminId identifiant de l'admin demandeur
  /// @throws ErreurMetier si l'utilisateur est le seul admin restant (409)
  ///                      ou si l'utilisateur n'est pas admin (400)
  public void initierRevocationAdmin(String userId, String adminId) {
    List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email, role::text AS role FROM users WHERE id = :id", Map.of("id", userId));
    if (cible.isEmpty()) throw new ErreurMetier("Utilisateur introuvable.", 404);
    if (!"admin".equals(cible.get(0).get("role"))) {
      throw new ErreurMetier("Cet utilisateur n'est pas administrateur.");
    }

    int nbAdmins =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin' AND revocation_admin_le IS NULL",
            Map.of(),
            Integer.class);
    if (nbAdmins <= 1) {
      throw new ErreurMetier("Impossible de révoquer le seul administrateur restant.", 409);
    }

    Instant dateEffective = Instant.now().plus(7, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE users SET revocation_admin_le = :date WHERE id = :id",
        Map.of("date", Timestamp.from(dateEffective), "id", userId));

    String dateFormatee =
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

  /// Annule une révocation admin en cours (remet revocation_admin_le à NULL).
  ///
  /// @param userId  identifiant de l'administrateur concerné
  /// @param adminId identifiant de l'admin demandeur
  /// @throws ErreurMetier si l'utilisateur n'existe pas ou n'a pas de révocation en cours
  public void annulerRevocationAdmin(String userId, String adminId) {
    int miseAJour =
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

  /// Supprime un compte utilisateur et notifie l'utilisateur par email.
  ///
  /// @param userId  identifiant de l'utilisateur à supprimer
  /// @param adminId identifiant de l'admin demandeur
  @Transactional
  public void supprimerUtilisateur(String userId, String adminId) {
    List<Map<String, Object>> cible =
        jdbc.queryForList("SELECT email FROM users WHERE id = :userId", Map.of("userId", userId));
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of("userId", userId));
    logAdminService.logAdmin(
        "warn", "admin", "compte supprimé", Map.of("userId", userId, "parAdmin", adminId));
    if (!cible.isEmpty()) {
      emailService.envoyerSuppressionCompte((String) cible.get(0).get("email"));
    }
  }

  /// Envoie un email de réinitialisation de mot de passe à un utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur cible
  /// @param adminId identifiant de l'admin demandeur
  /// @param baseUrl URL de base pour construire le lien
  /// @return l'email de l'utilisateur si trouvé, {@link Optional#empty()} sinon
  @Transactional
  public Optional<String> reinitialiserMdp(String userId, String adminId, String baseUrl) {
    List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT id, email FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) return Optional.empty();

    String email = (String) cible.get(0).get("email");
    String prefixeReset = "reset:" + email;
    verificationRepository.supprimerParIdentifiant(prefixeReset);

    String token = GenerateurSecrets.genererToken(32);
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(), prefixeReset, GenerateurSecrets.sha256Hex(token), expiresAt);

    emailService.envoyerResetMotDePasse(email, baseUrl + "/reset-password?token=" + token);
    logAdminService.logAdmin(
        "warn",
        "admin",
        "mot de passe réinitialisé",
        Map.of("userId", userId, "parAdmin", adminId));

    return Optional.of(email);
  }

  /// Réinitialise la 2FA TOTP d'un utilisateur : efface le secret et désactive la 2FA.
  ///
  /// <p>Utilisé quand un utilisateur perd l'accès à son application d'authentification
  /// (ou que son secret est désynchronisé) : il pourra se reconnecter sans code, puis
  /// ré-enrôler son TOTP.
  ///
  /// @param userId  identifiant de l'utilisateur cible
  /// @param adminId identifiant de l'admin à l'origine de l'action
  /// @return {@code true} si l'utilisateur existait, {@code false} sinon
  public boolean reinitialiserTotp(String userId, String adminId) {
    List<Map<String, Object>> cible =
        jdbc.queryForList("SELECT email FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) return false;

    jdbc.update(
        "UPDATE users SET two_factor_secret = NULL, two_factor_enabled = false WHERE id = :id",
        Map.of("id", userId));
    emailService.envoyer2faReinitialisee((String) cible.get(0).get("email"));
    logAdminService.logAdmin(
        "warn", "admin", "TOTP réinitialisé", Map.of("userId", userId, "parAdmin", adminId));
    return true;
  }

  /// Retourne le statut des clés de configuration autorisées.
  ///
  /// @return statuts de configuration
  public List<ConfigStatut> lireConfigStatuts() {
    Set<String> presentes = configService.lireConfigsPresentes();
    List<ConfigStatut> statuts = new ArrayList<>();
    for (String cle : CLES_AUTORISEES) {
      boolean presente = presentes.contains(cle);
      String valeur =
          (presente && CLES_EN_CLAIR.contains(cle))
              ? configService.lireConfig(cle).orElse(null)
              : null;
      statuts.add(new ConfigStatut(cle, presente, valeur, !CLES_EN_CLAIR.contains(cle)));
    }
    return statuts;
  }

  /// Valide et sauvegarde une clé de configuration.
  ///
  /// @param cle     clé à mettre à jour
  /// @param valeur  nouvelle valeur (null pour effacer)
  /// @param adminId identifiant de l'admin
  /// @throws ErreurMetier si la clé est non autorisée ou le format invalide
  public void sauvegarderConfig(String cle, String valeur, String adminId) {
    if (!CLES_AUTORISEES.contains(cle)) {
      throw new ErreurMetier("Clé de configuration invalide.");
    }
    if (valeur != null && valeur.isEmpty() && CLES_OBLIGATOIRES.contains(cle)) {
      throw new ErreurMetier("La valeur ne peut pas être vide pour " + cle + ".");
    }
    if (valeur != null && !valeur.isEmpty()) {
      String erreur = validerFormatConfig(cle, valeur);
      if (erreur != null) throw new ErreurMetier(erreur);
    }
    configService.sauvegarderConfig(cle, valeur == null || valeur.isEmpty() ? null : valeur);
    logAdminService.logAdmin("info", "admin", "Config sauvegardée : " + cle, Map.of("cle", cle));
  }

  /// Retourne les statistiques des jobs Quartz et le quota TM.
  ///
  /// @return map contenant statsJobs, quotaTM, etatQuota, workerLastSuccess, jobsEnCours
  public Map<String, Object> lireJobs() {
    List<StatJob> statsJobs =
        jdbc.query(
            """
            SELECT type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur
            FROM fetch_jobs
            ORDER BY type_job
            """,
            Map.of(),
            (rs, _) ->
                new StatJob(
                    rs.getString("type_job"),
                    rs.getTimestamp("derniere_exec") != null
                        ? rs.getTimestamp("derniere_exec").toInstant()
                        : null,
                    rs.getObject("duree_ms", Integer.class),
                    rs.getObject("nb_inseres", Integer.class),
                    rs.getObject("nb_mis_a_jour", Integer.class),
                    rs.getString("erreur"),
                    null));

    QuotaTM quotaTM = null;
    List<String> quotaJson =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = 'TM_QUOTA_JSON'",
            Map.of(),
            String.class);
    if (!quotaJson.isEmpty()) {
      try {
        quotaTM = objectMapper.readValue(quotaJson.get(0), QuotaTM.class);
      } catch (Exception _) {
        log.warn("Quota TM malformé — ignoré");
      }
    }

    Instant workerLastSuccess = null;
    try {
      java.sql.Timestamp ts =
          jdbc.queryForObject(
              "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE (erreur IS NULL OR erreur = '')",
              Map.of(),
              java.sql.Timestamp.class);
      workerLastSuccess = ts != null ? ts.toInstant() : null;
    } catch (Exception _) {
      log.debug("Impossible de lire le dernier job réussi");
    }

    List<String> jobsEnCours = workerProxyService.obtenirJobsEnCours();
    Map<String, Instant> prochainesExec = workerProxyService.obtenirProchainesExec();

    QuotaTM quotaFinal = quotaTM;
    List<StatJob> statsComplets =
        statsJobs.stream()
            .map(
                j ->
                    new StatJob(
                        j.typeJob(),
                        j.derniereExec(),
                        j.dureeMs(),
                        j.nbInseres(),
                        j.nbMisAJour(),
                        j.erreur(),
                        prochainesExec.get(j.typeJob())))
            .toList();

    String etatQuota = "ok";
    if (quotaFinal != null && quotaFinal.total() > 0) {
      int pct = quotaFinal.dispo() * 100 / quotaFinal.total();
      etatQuota = pct < 10 ? "critique" : pct < 20 ? "attention" : "ok";
    }

    Map<String, Object> reponse = new HashMap<>();
    reponse.put("statsJobs", statsComplets);
    reponse.put("quotaTM", quotaFinal);
    reponse.put("etatQuota", etatQuota);
    reponse.put("workerLastSuccess", workerLastSuccess);
    reponse.put("jobsEnCours", jobsEnCours);
    return reponse;
  }

  private String validerFormatConfig(String cle, String valeur) {
    if ("TICKETMASTER_API_KEY".equals(cle) && !FORMAT_TM_KEY.matcher(valeur).matches()) {
      return "Format de clé Ticketmaster invalide (16 à 64 caractères alphanumériques).";
    }
    if ("RESEND_API_KEY".equals(cle) && !FORMAT_RESEND_KEY.matcher(valeur).matches()) {
      return "La clé Resend doit commencer par re_ suivi de caractères alphanumériques.";
    }
    if ("RESEND_WEBHOOK_SECRET".equals(cle) && !FORMAT_WEBHOOK_SECRET.matcher(valeur).matches()) {
      return "La clé webhook Resend doit commencer par whsec_ suivi de caractères base64.";
    }
    if ("PUBLIC_BASE_URL".equals(cle)) {
      try {
        java.net.URI uri = java.net.URI.create(valeur);
        String host = uri.getHost();
        boolean estIp =
            "localhost".equals(host)
                || (host != null && host.matches("(\\d{1,3}\\.){3}\\d{1,3}"))
                || (host != null && host.contains(":") && host.matches("[0-9a-fA-F:]+"));
        if (!estIp && !"https".equals(uri.getScheme())) {
          return "L'URL doit commencer par https:// pour un domaine public.";
        }
      } catch (Exception _) {
        return "Format d'URL invalide.";
      }
    }
    return null;
  }

  /// Retourne les N derniers logs admin.
  ///
  /// @return liste des entrées de log
  public List<LogAdminService.EntreeLogAdmin> lireLogsAdmin() {
    return logAdminService.lireLogs(200);
  }
}
