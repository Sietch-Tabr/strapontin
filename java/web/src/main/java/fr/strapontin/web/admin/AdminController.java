/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST d'administration (portage de {@code admin/+page.server.ts}).
///
/// <p>Accessible uniquement aux utilisateurs avec le rôle {@code ADMIN}
/// (filtre Spring Security dans {@link fr.strapontin.web.securite.SecurityConfiguration}).
///
/// <p>Couvre : gestion des utilisateurs, configuration applicative,
/// génération des clés VAPID, lecture des logs et des stats de jobs.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);
  private static final Set<String> CLES_AUTORISEES =
      Set.of(
          "TICKETMASTER_API_KEY",
          "RESEND_API_KEY",
          "CONTACT_EMAIL",
          "PUBLIC_BASE_URL",
          "VAPID_PUBLIC_KEY",
          "VAPID_PRIVATE_KEY");
  private static final Set<String> CLES_EN_CLAIR = Set.of("CONTACT_EMAIL", "PUBLIC_BASE_URL");
  private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern FORMAT_TM_KEY = Pattern.compile("^[A-Za-z0-9]{16,64}$");
  private static final Pattern FORMAT_RESEND_KEY = Pattern.compile("^re_[A-Za-z0-9_-]{10,}$");

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final ObjectMapper objectMapper;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private final SecureRandom rng = new SecureRandom();

  public AdminController(
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      LogAdminService logAdminService,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.objectMapper = objectMapper;
  }

  // --- DTOs ---

  record UtilisateurAdmin(
      String id,
      String name,
      String email,
      String role,
      String username,
      boolean mustChangeCredentials,
      Instant createdAt,
      Instant derniereActivite) {}

  record ConfigStatut(String cle, boolean presente, String valeur, boolean secret) {}

  record StatJob(
      String typeJob,
      Instant derniereExec,
      Integer dureeMs,
      Integer nbInseres,
      Integer nbMisAJour,
      String erreur) {}

  record ReponseAdmin(
      List<UtilisateurAdmin> utilisateurs,
      Object quotaTM,
      List<StatJob> statsJobs,
      List<ConfigStatut> configStatuts,
      List<LogAdminService.EntreeLogAdmin> logsAdmin) {}

  record RequeteCreerUtilisateur(String email) {}

  record RequeteModifierUtilisateur(String nom, String email) {}

  record RequeteConfig(String cle, String valeur) {}

  // --- Endpoints utilisateurs ---

  /// Retourne la liste des utilisateurs avec leur dernière activité.
  ///
  /// @return les utilisateurs triés par date de création
  @GetMapping("/utilisateurs")
  public ResponseEntity<List<UtilisateurAdmin>> listerUtilisateurs() {
    List<Map<String, Object>> tousLesUsers =
        jdbc.queryForList(
            """
            SELECT id, name, email, role::text, username, must_change_credentials, created_at
            FROM users
            ORDER BY created_at
            """,
            Map.of());

    // Dernière activité via Spring Session (LAST_ACCESS_TIME en ms depuis epoch)
    Map<String, Long> activiteParUsername = new HashMap<>();
    jdbc.query(
        """
        SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) as derniere
        FROM SPRING_SESSION
        WHERE PRINCIPAL_NAME IS NOT NULL
        GROUP BY PRINCIPAL_NAME
        """,
        Map.of(),
        rs -> {
          activiteParUsername.put(rs.getString("PRINCIPAL_NAME"), rs.getLong("derniere"));
        });

    List<UtilisateurAdmin> utilisateurs =
        tousLesUsers.stream()
            .map(
                u -> {
                  String username = (String) u.get("username");
                  Long derniereMs = activiteParUsername.get(username);
                  Instant derniereActivite =
                      derniereMs != null ? Instant.ofEpochMilli(derniereMs) : null;
                  return new UtilisateurAdmin(
                      (String) u.get("id"),
                      (String) u.get("name"),
                      (String) u.get("email"),
                      (String) u.get("role"),
                      username,
                      (Boolean) u.get("must_change_credentials"),
                      ((java.sql.Timestamp) u.get("created_at")).toInstant(),
                      derniereActivite);
                })
            .toList();

    return ResponseEntity.ok(utilisateurs);
  }

  /// Crée un nouveau compte utilisateur et envoie un email d'invitation.
  ///
  /// @param requete corps JSON avec l'email
  /// @param auth    contexte d'authentification admin
  /// @return 201 avec l'identifiant temporaire, ou 400 en cas d'erreur
  @PostMapping("/utilisateurs")
  public ResponseEntity<Map<String, Object>> creerUtilisateur(
      @RequestBody RequeteCreerUtilisateur requete, Authentication auth) {
    String adminId = utilisateur(auth).id();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();

    if (email.isEmpty() || !FORMAT_EMAIL.matcher(email).matches()) {
      return erreur(400, "Format d'email invalide.");
    }

    boolean emailExistant =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE email = :email", Map.of("email", email), Integer.class)
            .isEmpty();
    if (emailExistant) {
      return erreur(400, "Impossible de créer le compte. Email déjà utilisé ?");
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
        Map.of(
            "id", accountId,
            "accountId", userId,
            "userId", userId,
            "password", hashMdp));

    // Token de reset MDP (invitation = 24h)
    String token = genererToken(32);
    String tokenId = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

    jdbc.update(
        """
        INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
        VALUES (:id, :identifier, :value, :expiresAt, now(), now())
        """,
        Map.of(
            "id", tokenId,
            "identifier", email,
            "value", token,
            "expiresAt", java.sql.Timestamp.from(expiresAt)));

    String baseUrl =
        configService.lireConfig("PUBLIC_BASE_URL").orElse("https://strapontin.kwaite.fr");
    String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerInvitation(email, usernameTemp, lienReset);

    logAdminService.logAdmin(
        "info", "admin", "compte créé", Map.of("email", email, "parAdmin", adminId));

    return ResponseEntity.status(201)
        .body(Map.of("succes", true, "email", email, "identifiant", usernameTemp, "lienReset", lienReset));
  }

  /// Modifie le nom et/ou l'email d'un utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur à modifier
  /// @param requete corps JSON avec {@code nom} et/ou {@code email}
  /// @param auth    contexte d'authentification admin
  /// @return 200 si modifié, 400 en cas d'erreur
  @PatchMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> modifierUtilisateur(
      @PathVariable String userId,
      @RequestBody RequeteModifierUtilisateur requete,
      Authentication auth) {
    String adminId = utilisateur(auth).id();

    if (userId.equals(adminId)) {
      return erreur(400, "Utilisez /parametres pour modifier votre propre compte.");
    }

    String nom = requete.nom() == null ? "" : requete.nom().strip();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();

    if (!nom.isEmpty() && nom.length() > 100) {
      return erreur(400, "Nom trop long (100 caractères max).");
    }
    if (!email.isEmpty() && !FORMAT_EMAIL.matcher(email).matches()) {
      return erreur(400, "Format d'email invalide.");
    }

    if (!email.isEmpty()) {
      boolean emailPris =
          !jdbc.queryForList(
                  "SELECT 1 FROM users WHERE email = :email AND id != :id",
                  Map.of("email", email, "id", userId),
                  Integer.class)
              .isEmpty();
      if (emailPris) {
        return erreur(400, "Cette adresse email est déjà utilisée.");
      }
    }

    Map<String, Object> miseAJour = new HashMap<>();
    miseAJour.put("updatedAt", java.sql.Timestamp.from(Instant.now()));
    miseAJour.put("userId", userId);
    if (!nom.isEmpty()) miseAJour.put("nom", nom);
    if (!email.isEmpty()) miseAJour.put("email", email);

    StringBuilder setClauses = new StringBuilder("updated_at = :updatedAt");
    if (!nom.isEmpty()) setClauses.append(", name = :nom");
    if (!email.isEmpty()) setClauses.append(", email = :email");

    jdbc.update("UPDATE users SET " + setClauses + " WHERE id = :userId", miseAJour);

    if (!email.isEmpty()) {
      logAdminService.logAdmin(
          "warn",
          "admin",
          "email modifié",
          Map.of("userId", userId, "nouvelEmail", email, "parAdmin", adminId));
    }
    return ResponseEntity.ok(Map.of("succes", true, "action", "modifierCompte"));
  }

  /// Supprime un compte utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur à supprimer
  /// @param auth   contexte d'authentification admin
  /// @return 204 si supprimé, 400 si tentative d'auto-suppression
  @DeleteMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> supprimerUtilisateur(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();

    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de supprimer ce compte.");
    }

    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of("userId", userId));
    logAdminService.logAdmin(
        "warn", "admin", "compte supprimé", Map.of("userId", userId, "parAdmin", adminId));
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Envoie un email de réinitialisation de mot de passe à un utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param auth   contexte d'authentification admin
  /// @return 200 si envoyé, 400/404 en cas d'erreur
  @PostMapping("/utilisateurs/{userId}/reset-mdp")
  public ResponseEntity<Map<String, Object>> reinitialiserMdp(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();

    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }

    List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT id, email, name FROM users WHERE id = :userId", Map.of("userId", userId));
    if (cible.isEmpty()) {
      return erreur(404, "Utilisateur introuvable.");
    }

    String email = (String) cible.get(0).get("email");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    if (baseUrl.isEmpty()) {
      return erreur(400, "PUBLIC_BASE_URL n'est pas configurée.");
    }

    // Supprimer les anciens tokens pour cet email
    jdbc.update("DELETE FROM verifications WHERE identifier = :email", Map.of("email", email));

    String token = genererToken(32);
    String tokenId = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
    jdbc.update(
        """
        INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
        VALUES (:id, :identifier, :value, :expiresAt, now(), now())
        """,
        Map.of(
            "id", tokenId,
            "identifier", email,
            "value", token,
            "expiresAt", java.sql.Timestamp.from(expiresAt)));

    String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerResetMotDePasse(email, lienReset);

    logAdminService.logAdmin(
        "warn",
        "admin",
        "mot de passe réinitialisé",
        Map.of("userId", userId, "parAdmin", adminId));

    return ResponseEntity.ok(Map.of("succes", true, "action", "reinitialiserMdp", "email", email));
  }

  // --- Endpoints configuration ---

  /// Retourne le statut des clés de configuration (présence + valeur en clair pour CONTACT_EMAIL et
  // PUBLIC_BASE_URL).
  ///
  /// @return la liste des statuts de configuration
  @GetMapping("/config")
  public ResponseEntity<List<ConfigStatut>> lireConfigStatuts() {
    Set<String> presentes = configService.lireConfigsPresentes();
    List<ConfigStatut> statuts = new ArrayList<>();

    for (String cle : CLES_AUTORISEES) {
      boolean presente = presentes.contains(cle);
      String valeur = null;
      if (presente && CLES_EN_CLAIR.contains(cle)) {
        valeur = configService.lireConfig(cle).orElse(null);
      }
      statuts.add(new ConfigStatut(cle, presente, valeur, !CLES_EN_CLAIR.contains(cle)));
    }

    return ResponseEntity.ok(statuts);
  }

  /// Sauvegarde une valeur de configuration applicative.
  ///
  /// @param requete corps JSON avec {@code cle} et {@code valeur}
  /// @param auth    contexte d'authentification admin
  /// @return 200 si sauvegardé, 400 si clé non autorisée ou format invalide
  @PatchMapping("/config")
  public ResponseEntity<Map<String, Object>> sauvegarderConfig(
      @RequestBody RequeteConfig requete, Authentication auth) {
    String cle = requete.cle() == null ? "" : requete.cle().strip();
    String valeur = requete.valeur() == null ? "" : requete.valeur().strip();

    if (!CLES_AUTORISEES.contains(cle)) {
      return erreur(400, "Clé de configuration invalide.");
    }

    if (!valeur.isEmpty()) {
      ResponseEntity<Map<String, Object>> erreurValidation = validerConfig(cle, valeur);
      if (erreurValidation != null) return erreurValidation;
    }

    configService.sauvegarderConfig(cle, valeur.isEmpty() ? null : valeur);
    return ResponseEntity.ok(Map.of("succes", true, "action", "sauvegarderConfig", "cle", cle));
  }

  // --- Endpoints VAPID ---

  /// Génère une nouvelle paire de clés VAPID EC P-256 et la sauvegarde en configuration.
  ///
  /// @return 200 si générées
  @PostMapping("/vapid/generer")
  public ResponseEntity<Map<String, Object>> genererVapid() {
    try {
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
      kpg.initialize(new ECGenParameterSpec("prime256v1"));
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

      return ResponseEntity.ok(Map.of("succes", true, "action", "genererVapid"));
    } catch (Exception e) {
      log.error("Erreur génération clés VAPID", e);
      return erreur(500, "Erreur lors de la génération des clés VAPID.");
    }
  }

  // --- Endpoints logs & jobs ---

  /// Retourne les 100 derniers logs admin.
  ///
  /// @return la liste des entrées de log
  @GetMapping("/logs")
  public ResponseEntity<List<LogAdminService.EntreeLogAdmin>> lireLogs() {
    return ResponseEntity.ok(logAdminService.lireLogs(100));
  }

  /// Retourne les statistiques des jobs Quartz et le quota TM.
  ///
  /// @return les stats de jobs et le quota TM
  @GetMapping("/jobs")
  public ResponseEntity<Map<String, Object>> lireJobs() {
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
                    rs.getString("erreur")));

    // Quota TM (stocké en JSON brut dans app_config)
    Object quotaTM = null;
    List<String> quotaJson =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = 'TM_QUOTA_JSON'",
            Map.of(),
            String.class);
    if (!quotaJson.isEmpty()) {
      try {
        quotaTM = objectMapper.readValue(quotaJson.get(0), Object.class);
      } catch (Exception e) {
        log.warn("Quota TM malformé — ignoré");
      }
    }

    Map<String, Object> reponse = new HashMap<>();
    reponse.put("statsJobs", statsJobs);
    reponse.put("quotaTM", quotaTM);
    return ResponseEntity.ok(reponse);
  }

  /// Déclenche immédiatement les fetches (appel au worker via HTTP interne).
  ///
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  @PostMapping("/relancer")
  public ResponseEntity<Map<String, Object>> relancerFetches() {
    try {
      java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
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
      return erreur(503, "Worker inaccessible (statut " + rep.statusCode() + ").");
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger", e);
      return erreur(503, "Worker inaccessible : " + e.getMessage());
    }
  }

  // --- Utilitaires ---

  private ResponseEntity<Map<String, Object>> validerConfig(String cle, String valeur) {
    if ("TICKETMASTER_API_KEY".equals(cle) && !FORMAT_TM_KEY.matcher(valeur).matches()) {
      return erreur(
          400, "Format de clé Ticketmaster invalide (16 à 64 caractères alphanumériques).");
    }
    if ("RESEND_API_KEY".equals(cle) && !FORMAT_RESEND_KEY.matcher(valeur).matches()) {
      return erreur(
          400, "La clé Resend doit commencer par re_ suivi de caractères alphanumériques.");
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
          return erreur(400, "L'URL doit commencer par https:// pour un domaine public.");
        }
      } catch (Exception e) {
        return erreur(400, "Format d'URL invalide.");
      }
    }
    return null;
  }

  private String genererHex(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    StringBuilder sb = new StringBuilder();
    for (byte b : octets) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private String genererToken(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
  }

  private static byte[] vers32Octets(BigInteger n) {
    byte[] raw = n.toByteArray();
    if (raw.length == 32) return raw;
    byte[] result = new byte[32];
    if (raw.length > 32) {
      // Supprimer le byte de signe éventuel
      System.arraycopy(raw, raw.length - 32, result, 0, 32);
    } else {
      // Compléter avec des zéros en tête
      System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
    }
    return result;
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
