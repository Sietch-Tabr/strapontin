/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.strapontin.web.config.ConfigService;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint REST de configuration initiale de l'application.
///
/// <p>Permet de créer le premier administrateur sur une instance vierge.
/// Accessible sans authentification ({@code permitAll} dans
/// {@link fr.strapontin.web.securite.SecurityConfiguration}).
/// Retourne 409 si des utilisateurs existent déjà.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/setup")
public class SetupController {

  private static final Logger log = LoggerFactory.getLogger(SetupController.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

  public SetupController(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  // --- DTOs ---

  record ReponseSetup(@JsonProperty("deja_configure") boolean dejaConfiguree) {}

  record RequeteSetup(
      String nom,
      String identifiant,
      String email,
      String motDePasse,
      String baseUrl,
      String contactEmail) {}

  // --- Endpoints ---

  /// Vérifie si l'application est déjà configurée (table {@code users} non vide).
  ///
  /// @return {@code { "deja_configure": true }} si au moins un utilisateur existe
  @GetMapping
  public ResponseEntity<ReponseSetup> verifierSetup() {
    Integer nbUsers = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class);
    return ResponseEntity.ok(new ReponseSetup(nbUsers != null && nbUsers > 0));
  }

  /// Crée le premier administrateur et initialise la configuration applicative.
  ///
  /// <p>Génère également une paire de clés VAPID. Retourne 409 si des utilisateurs
  /// existent déjà.
  ///
  /// @param requete les données d'initialisation
  /// @return 201 si créé, 400 si validation échoue, 409 si déjà configuré
  @PostMapping
  public ResponseEntity<Map<String, Object>> initialiser(@RequestBody RequeteSetup requete) {
    Integer nbUsers = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class);
    if (nbUsers != null && nbUsers > 0) {
      return erreur(409, "L'application est déjà configurée.");
    }

    String nom = requete.nom() == null ? "" : requete.nom().strip();
    String identifiant =
        requete.identifiant() == null ? "" : requete.identifiant().strip().toLowerCase();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();
    String motDePasse = requete.motDePasse() == null ? "" : requete.motDePasse();

    if (nom.isEmpty()) return erreur(400, "Le nom est requis.");
    if (identifiant.length() < 3 || !identifiant.matches("[a-z0-9_]+")) {
      return erreur(
          400,
          "L'identifiant doit contenir au moins 3 caractères (lettres minuscules, chiffres, _).");
    }
    if (email.isEmpty() || !email.contains("@")) {
      return erreur(400, "L'adresse email est invalide.");
    }
    if (motDePasse.length() < 12) {
      return erreur(400, "Le mot de passe doit contenir au moins 12 caractères.");
    }

    String userId = UUID.randomUUID().toString();
    String hashMdp = bcrypt.encode(motDePasse);

    Map<String, Object> paramsUser = new HashMap<>();
    paramsUser.put("id", userId);
    paramsUser.put("name", nom);
    paramsUser.put("email", email);
    paramsUser.put("username", identifiant);
    paramsUser.put("displayUsername", identifiant);
    jdbc.update(
        """
        INSERT INTO users (id, name, email, email_verified, role, username, display_username,
                           must_change_credentials, created_at, updated_at)
        VALUES (:id, :name, :email, true, 'admin', :username, :displayUsername,
                false, now(), now())
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

    String baseUrl = requete.baseUrl() == null ? "" : requete.baseUrl().strip();
    String contactEmail = requete.contactEmail() == null ? "" : requete.contactEmail().strip();
    if (!baseUrl.isEmpty()) configService.sauvegarderConfig("PUBLIC_BASE_URL", baseUrl);
    if (!contactEmail.isEmpty()) configService.sauvegarderConfig("CONTACT_EMAIL", contactEmail);

    genererVapid();

    log.info("Premier administrateur créé : {}", identifiant);
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  // --- Utilitaires ---

  private void genererVapid() {
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
    } catch (Exception e) {
      log.warn("Impossible de générer les clés VAPID lors du setup", e);
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

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
