/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.VerificationRepository;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service d'administration : gestion des utilisateurs, jobs, configuration et clés VAPID.
///
/// <p>Contient la logique métier et l'accès aux données pour le domaine admin.
/// {@link AdminController.creerUtilisateur} est atomique ({@code @Transactional}).
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
          "VAPID_PUBLIC_KEY",
          "VAPID_PRIVATE_KEY");
  private static final Set<String> CLES_EN_CLAIR = Set.of("CONTACT_EMAIL", "PUBLIC_BASE_URL");
  private static final Set<String> CLES_OBLIGATOIRES = Set.of("CONTACT_EMAIL", "PUBLIC_BASE_URL");
  private static final Pattern FORMAT_TM_KEY = Pattern.compile("^[A-Za-z0-9]{16,64}$");
  private static final Pattern FORMAT_RESEND_KEY = Pattern.compile("^re_[A-Za-z0-9_-]{10,}$");
  private static final Pattern FORMAT_WEBHOOK_SECRET =
      Pattern.compile("^whsec_[A-Za-z0-9+/=]{16,}$");
  private static final Set<String> ROLES_VALIDES = Set.of("user", "admin");

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final ObjectMapper objectMapper;
  private final VerificationRepository verificationRepository;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private final SecureRandom rng = new SecureRandom();

  /// @param jdbc                  accès JDBC
  /// @param configService         lecture/écriture de la configuration applicative
  /// @param emailService          service d'envoi d'emails
  /// @param logAdminService       service de logs admin
  /// @param objectMapper          désérialiseur JSON (quota TM)
  /// @param verificationRepository accès aux tokens de vérification
  public AdminService(
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      LogAdminService logAdminService,
      ObjectMapper objectMapper,
      VerificationRepository verificationRepository) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.objectMapper = objectMapper;
    this.verificationRepository = verificationRepository;
  }

  /// Retourne la liste des utilisateurs avec leur dernière activité.
  ///
  /// @return utilisateurs triés par date de création
  public List<UtilisateurAdmin> listerUtilisateurs() {
    List<Map<String, Object>> tousLesUsers =
        jdbc.queryForList(
            """
            SELECT id, name, email, role::text, username, must_change_credentials, created_at
            FROM users
            ORDER BY created_at
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
              return new UtilisateurAdmin(
                  (String) u.get("id"),
                  (String) u.get("name"),
                  (String) u.get("email"),
                  (String) u.get("role"),
                  username,
                  (Boolean) u.get("must_change_credentials"),
                  ((java.sql.Timestamp) u.get("created_at")).toInstant(),
                  derniereMs != null ? Instant.ofEpochMilli(derniereMs) : null);
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
    String usernameTemp = "u_" + genererHex(4);
    String motDePasseTemp = genererToken(12);
    String hashMdp = bcrypt.encode(motDePasseTemp);
    String nomInitial =
        email.split("@")[0].substring(0, Math.min(email.split("@")[0].length(), 100));

    Map<String, Object> paramsUser = new HashMap<>();
    paramsUser.put("id", userId);
    paramsUser.put("name", nomInitial);
    paramsUser.put("email", email);
    paramsUser.put("username", usernameTemp);
    paramsUser.put("displayUsername", usernameTemp);
    jdbc.update(
        """
        INSERT INTO users (id, name, email, email_verified, role, username, display_username,
                           must_change_credentials, created_at, updated_at)
        VALUES (:id, :name, :email, false, 'user', :username, :displayUsername,
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

    String token = genererToken(32);
    Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(), "invite:" + email, sha256Hex(token), expiresAt);

    String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerInvitation(email, usernameTemp, lienReset);
    logAdminService.logAdmin(
        "info", "admin", "compte créé", Map.of("email", email, "parAdmin", adminId));

    return Optional.of(new ResultatCreation(email, usernameTemp, lienReset));
  }

  /// Valide et met à jour les champs d'un utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur cible
  /// @param requete champs à modifier
  /// @param adminId identifiant de l'admin demandeur
  /// @throws ErreurMetier si les données sont invalides ou l'email est déjà utilisé
  public void modifierUtilisateur(
      String userId, RequeteModifierUtilisateur requete, String adminId) {
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();
    String role = requete.role() == null ? "" : requete.role().strip().toLowerCase();

    if (nom.isEmpty() && email.isEmpty() && role.isEmpty()) {
      throw new ErreurMetier("Au moins un champ à modifier est requis.");
    }
    if (!nom.isEmpty() && nom.length() > 100) {
      throw new ErreurMetier("Nom trop long (100 caractères max).");
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
    if (!nom.isEmpty()) setClauses.append(", name = :nom");
    if (!email.isEmpty()) setClauses.append(", email = :email");
    if (!role.isEmpty()) setClauses.append(", role = CAST(:role AS user_role)");
    jdbc.update("UPDATE users SET " + setClauses + " WHERE id = :userId", miseAJour);

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
    }
  }

  /// Supprime un compte utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur à supprimer
  /// @param adminId identifiant de l'admin demandeur
  public void supprimerUtilisateur(String userId, String adminId) {
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of("userId", userId));
    logAdminService.logAdmin(
        "warn", "admin", "compte supprimé", Map.of("userId", userId, "parAdmin", adminId));
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

    String token = genererToken(32);
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    verificationRepository.inserer(
        UUID.randomUUID().toString(), prefixeReset, sha256Hex(token), expiresAt);

    emailService.envoyerResetMotDePasse(email, baseUrl + "/reset-password?token=" + token);
    logAdminService.logAdmin(
        "warn",
        "admin",
        "mot de passe réinitialisé",
        Map.of("userId", userId, "parAdmin", adminId));

    return Optional.of(email);
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

  /// Génère une paire de clés VAPID EC P-256 et supprime les abonnements push existants.
  ///
  /// @return paire de clés encodées en Base64 URL-safe
  /// @throws Exception si la génération de clés échoue
  public ResultatVapid genererVapid() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair paire = kpg.generateKeyPair();

    ECPublicKey pubKey = (ECPublicKey) paire.getPublic();
    ECPoint w = pubKey.getW();
    byte[] uncompressed = new byte[65];
    uncompressed[0] = 0x04;
    byte[] x = vers32Octets(w.getAffineX());
    byte[] y = vers32Octets(w.getAffineY());
    System.arraycopy(x, 0, uncompressed, 1, 32);
    System.arraycopy(y, 0, uncompressed, 33, 32);
    String clePublique = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressed);

    ECPrivateKey privKey = (ECPrivateKey) paire.getPrivate();
    byte[] d = vers32Octets(privKey.getS());
    String clePrivee = Base64.getUrlEncoder().withoutPadding().encodeToString(d);

    configService.sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique);
    configService.sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee);

    int nbSupprimes = jdbc.update("DELETE FROM push_abonnements", Map.of());
    logAdminService.logAdmin(
        "warn",
        "admin",
        "Clés VAPID régénérées — " + nbSupprimes + " abonnement(s) supprimé(s)",
        Map.of("nbAbonnements", nbSupprimes));

    return new ResultatVapid(clePublique, nbSupprimes);
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

    List<String> jobsEnCours = obtenirJobsEnCours();
    Map<String, Instant> prochainesExec = obtenirProchainerExec();

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

  private List<String> obtenirJobsEnCours() {
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      java.net.http.HttpRequest req =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create("http://localhost:8081/trigger/actif"))
              .GET()
              .timeout(java.time.Duration.ofSeconds(2))
              .build();
      java.net.http.HttpResponse<String> rep =
          client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (rep.statusCode() == 200) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(rep.body(), Map.class);
        Object actifs = data.get("actifs");
        if (actifs instanceof java.util.List<?> liste) {
          return liste.stream().map(Object::toString).toList();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.debug("Worker inaccessible pour jobs actifs");
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Instant> obtenirProchainerExec() {
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      java.net.http.HttpRequest req =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create("http://localhost:8081/trigger/prochaines-exec"))
              .GET()
              .timeout(java.time.Duration.ofSeconds(2))
              .build();
      java.net.http.HttpResponse<String> rep =
          client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (rep.statusCode() == 200) {
        Map<String, Object> data = objectMapper.readValue(rep.body(), Map.class);
        Map<String, Instant> resultat = new HashMap<>();
        data.forEach(
            (k, v) -> {
              if (v instanceof Number n) resultat.put(k, Instant.ofEpochMilli(n.longValue()));
            });
        return resultat;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.debug("Worker inaccessible pour prochaines exécutions");
    }
    return Map.of();
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

  private String genererHex(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    StringBuilder sb = new StringBuilder();
    for (byte b : octets) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private String genererToken(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 non disponible", e);
    }
  }

  private static byte[] vers32Octets(BigInteger n) {
    byte[] raw = n.toByteArray();
    if (raw.length == 32) return raw;
    byte[] result = new byte[32];
    if (raw.length > 32) {
      System.arraycopy(raw, raw.length - 32, result, 0, 32);
    } else {
      System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
    }
    return result;
  }

  /// Retourne les N derniers logs admin.
  ///
  /// @return liste des entrées de log
  public List<LogAdminService.EntreeLogAdmin> lireLogsAdmin() {
    return logAdminService.lireLogs(200);
  }

  /// Publie une bannière système.
  ///
  /// @param message texte de la bannière
  /// @param type    type ({@code info}, {@code warning} ou {@code error})
  public void publierBanniere(String message, String type) {
    configService.sauvegarderConfig("BANNER_MESSAGE", message);
    configService.sauvegarderConfig("BANNER_TYPE", type);
    configService.sauvegarderConfig("BANNER_ID", Instant.now().toString());
    logAdminService.logAdmin("info", "admin", "Bannière publiée : " + type);
  }

  /// Efface la bannière système.
  public void effacerBanniere() {
    configService.sauvegarderConfig("BANNER_MESSAGE", null);
    configService.sauvegarderConfig("BANNER_TYPE", null);
    configService.sauvegarderConfig("BANNER_ID", null);
    logAdminService.logAdmin("info", "admin", "Bannière effacée");
  }

  /// Déclenche les fetches via le worker (appel HTTP interne).
  ///
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  public ResponseEntity<Map<String, Object>> relancerFetches() {
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      java.net.http.HttpRequest req =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create("http://localhost:8081/trigger/tout"))
              .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
              .build();
      java.net.http.HttpResponse<String> rep =
          client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (rep.statusCode() == 200) {
        logAdminService.logAdmin("info", "admin", "Fetches relancés manuellement");
        return ResponseEntity.ok(Map.of("statut", "déclenché"));
      }
      Map<String, Object> corps = new HashMap<>();
      corps.put("erreur", "Worker inaccessible (statut " + rep.statusCode() + ").");
      return ResponseEntity.status(503).body(corps);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Map<String, Object> corps = new HashMap<>();
      corps.put("erreur", "Requête interrompue.");
      return ResponseEntity.status(503).body(corps);
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger", e);
      Map<String, Object> corps = new HashMap<>();
      corps.put("erreur", "Worker inaccessible.");
      return ResponseEntity.status(503).body(corps);
    }
  }

  /// Résultat de la création d'un utilisateur.
  public record ResultatCreation(String email, String identifiant, String lienReset) {}

  /// Résultat de la génération des clés VAPID.
  public record ResultatVapid(String clePublique, int nbAbonnementsSupprimes) {}
}
