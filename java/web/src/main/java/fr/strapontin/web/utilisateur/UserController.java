/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour le profil utilisateur courant.
///
/// <p>Gère la lecture et mise à jour du profil, le changement de mot de passe
/// et la suppression de compte. Également le flow de première connexion
/// ({@code must_change_credentials}).
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/user")
public class UserController {

  private static final Pattern FORMAT_EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
  private static final Pattern FORMAT_IDENTIFIANT = Pattern.compile("^[a-zA-Z0-9_]+$");

  private final NamedParameterJdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;

  public UserController(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
  }

  record ProfilUtilisateur(String id, String nom, String email, String identifiant) {}

  record RequeteProfil(
      @Size(min = 1, max = 100, message = "Nom invalide (1-100 caractères).") String nom,
      @Email(message = "Format d'email invalide.") @Size(max = 254) String email) {}

  record RequeteIdentifiant(
      @NotBlank(message = "L'identifiant est obligatoire.")
          @Size(min = 3, max = 30, message = "L'identifiant doit contenir entre 3 et 30 caractères.")
          @jakarta.validation.constraints.Pattern(
              regexp = "[a-zA-Z0-9_]+",
              message = "L'identifiant ne peut contenir que des lettres, chiffres et underscores.")
          String identifiant) {}

  record RequeteMotDePasse(
      @NotBlank(message = "Tous les champs sont obligatoires.") String motDePasseActuel,
      @NotBlank(message = "Tous les champs sont obligatoires.")
          @Size(min = 12, message = "Le nouveau mot de passe doit contenir au moins 12 caractères.")
          String motDePasseNouveau) {}

  record RequetePremierLogin(
      @NotBlank(message = "Tous les champs sont obligatoires.")
          @Size(max = 100, message = "Nom invalide (1-100 caractères).")
          String nom,
      @NotBlank(message = "Tous les champs sont obligatoires.")
          @Size(min = 3, max = 30, message = "L'identifiant doit contenir entre 3 et 30 caractères.")
          String identifiant,
      @NotBlank(message = "Tous les champs sont obligatoires.") String motDePasseActuel,
      @NotBlank(message = "Tous les champs sont obligatoires.")
          @Size(min = 12, message = "Le mot de passe doit contenir au moins 12 caractères.")
          String motDePasseNouveau) {}

  /// Retourne le profil de l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return le profil (id, nom, email, identifiant)
  @GetMapping("/profile")
  public ResponseEntity<ProfilUtilisateur> lireProfil(Authentication auth) {
    UtilisateurDetails details = utilisateur(auth);
    return ResponseEntity.ok(
        new ProfilUtilisateur(details.id(), details.nom(), details.email(), details.identifiant()));
  }

  /// Met à jour le nom et/ou l'email de l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code nom} et/ou {@code email}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si invalide
  @PatchMapping("/profile")
  public ResponseEntity<Map<String, Object>> majProfil(
      @Valid @RequestBody RequeteProfil requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (requete.nom() != null) {
      String nom = requete.nom().strip();
      if (nom.isEmpty() || nom.length() > 100) {
        return erreur(400, "Nom invalide (1-100 caractères).");
      }
      jdbc.update(
          "UPDATE users SET name = :nom, updated_at = now() WHERE id = :userId",
          Map.of("nom", nom, "userId", userId));
    }

    if (requete.email() != null) {
      String email = requete.email().strip().toLowerCase();
      if (!FORMAT_EMAIL.matcher(email).matches() || email.length() > 254) {
        return erreur(400, "Format d'email invalide.");
      }
      boolean emailDejaUtilise =
          !jdbc.queryForList(
                  "SELECT 1 FROM users WHERE email = :email AND id != :userId",
                  Map.of("email", email, "userId", userId),
                  Integer.class)
              .isEmpty();
      if (emailDejaUtilise) {
        return erreur(400, "Cette adresse email est déjà utilisée.");
      }
      jdbc.update(
          "UPDATE users SET email = :email, updated_at = now() WHERE id = :userId",
          Map.of("email", email, "userId", userId));
    }

    return ResponseEntity.ok(Map.of("succes", "profil"));
  }

  /// Met à jour l'identifiant (username) de l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code identifiant}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si invalide ou déjà utilisé
  @PatchMapping("/identifiant")
  public ResponseEntity<Map<String, Object>> majIdentifiant(
      @Valid @RequestBody RequeteIdentifiant requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String identifiant =
        requete.identifiant() == null ? "" : requete.identifiant().strip().toLowerCase();

    if (identifiant.length() < 3 || identifiant.length() > 30) {
      return erreur(400, "L'identifiant doit contenir entre 3 et 30 caractères.");
    }
    if (!FORMAT_IDENTIFIANT.matcher(identifiant).matches()) {
      return erreur(
          400, "L'identifiant ne peut contenir que des lettres, chiffres et underscores.");
    }

    boolean dejaUtilise =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE lower(username) = :ident AND id != :userId",
                Map.of("ident", identifiant, "userId", userId),
                Integer.class)
            .isEmpty();
    if (dejaUtilise) {
      return erreur(400, "Cet identifiant est déjà utilisé.");
    }

    jdbc.update(
        """
        UPDATE users SET username = :ident, display_username = :ident, updated_at = now()
        WHERE id = :userId
        """,
        Map.of("ident", identifiant, "userId", userId));

    return ResponseEntity.ok(Map.of("succes", "identifiant"));
  }

  /// Change le mot de passe de l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code motDePasseActuel} et {@code motDePasseNouveau}
  /// @param auth    contexte d'authentification
  /// @return 200 si changé, 400 si invalide ou mot de passe actuel incorrect
  @PostMapping("/password")
  public ResponseEntity<Map<String, Object>> changerMotDePasse(
      @Valid @RequestBody RequeteMotDePasse requete, Authentication auth) {
    UtilisateurDetails details = utilisateur(auth);

    if (requete.motDePasseActuel() == null || requete.motDePasseNouveau() == null) {
      return erreur(400, "Tous les champs sont obligatoires.");
    }
    if (requete.motDePasseNouveau().length() < 12) {
      return erreur(400, "Le nouveau mot de passe doit contenir au moins 12 caractères.");
    }
    if (!passwordEncoder.matches(requete.motDePasseActuel(), details.motDePasse())) {
      return erreur(400, "Mot de passe actuel incorrect.");
    }

    String nouveauHash = passwordEncoder.encode(requete.motDePasseNouveau());
    jdbc.update(
        "UPDATE accounts SET password = :hash WHERE user_id = :userId AND provider_id ="
            + " 'credential'",
        Map.of("hash", nouveauHash, "userId", details.id()));

    return ResponseEntity.ok(Map.of("succes", "motDePasse"));
  }

  /// Complète la première connexion : nom, identifiant, mot de passe, et lève {@code
  // must_change_credentials}.
  ///
  /// @param requete    corps JSON avec nom, identifiant, motDePasseActuel, motDePasseNouveau
  /// @param auth       contexte d'authentification
  /// @param httpRequest pour invalider la session après changement
  /// @return 200 si terminé, 400 si invalide
  @PostMapping("/first-login")
  public ResponseEntity<Map<String, Object>> premierLogin(
      @Valid @RequestBody RequetePremierLogin requete,
      Authentication auth,
      HttpServletRequest httpRequest) {
    UtilisateurDetails details = utilisateur(auth);

    if (!details.doitChangerCredentials()) {
      return erreur(403, "Accès refusé.");
    }
    if (requete.nom() == null
        || requete.identifiant() == null
        || requete.motDePasseActuel() == null
        || requete.motDePasseNouveau() == null) {
      return erreur(400, "Tous les champs sont obligatoires.");
    }

    String nom = requete.nom().strip();
    String identifiant = requete.identifiant().strip().toLowerCase();

    if (nom.isEmpty() || nom.length() > 100) {
      return erreur(400, "Nom invalide (1-100 caractères).");
    }
    if (identifiant.length() < 3 || identifiant.length() > 30) {
      return erreur(400, "L'identifiant doit contenir entre 3 et 30 caractères.");
    }
    if (!FORMAT_IDENTIFIANT.matcher(identifiant).matches()) {
      return erreur(
          400, "L'identifiant ne peut contenir que des lettres, chiffres et underscores.");
    }
    if (requete.motDePasseNouveau().length() < 12) {
      return erreur(400, "Le mot de passe doit contenir au moins 12 caractères.");
    }
    if (!passwordEncoder.matches(requete.motDePasseActuel(), details.motDePasse())) {
      return erreur(400, "Mot de passe temporaire incorrect.");
    }

    boolean identDejaUtilise =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE lower(username) = :ident AND id != :userId",
                Map.of("ident", identifiant, "userId", details.id()),
                Integer.class)
            .isEmpty();
    if (identDejaUtilise) {
      return erreur(400, "Cet identifiant est déjà utilisé.");
    }

    String nouveauHash = passwordEncoder.encode(requete.motDePasseNouveau());
    jdbc.update(
        """
        UPDATE users SET name = :nom, username = :ident, display_username = :ident,
               must_change_credentials = false, updated_at = now()
        WHERE id = :userId
        """,
        Map.of("nom", nom, "ident", identifiant, "userId", details.id()));
    jdbc.update(
        "UPDATE accounts SET password = :hash WHERE user_id = :userId AND provider_id ="
            + " 'credential'",
        Map.of("hash", nouveauHash, "userId", details.id()));

    // Invalider la session pour forcer une reconnexion avec le nouveau mot de passe
    HttpSession session = httpRequest.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();

    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Supprime le compte de l'utilisateur courant (cascade sur toutes ses données).
  ///
  /// @param auth       contexte d'authentification
  /// @param httpRequest pour invalider la session
  /// @return 204 si supprimé
  @DeleteMapping
  public ResponseEntity<Void> supprimerCompte(Authentication auth, HttpServletRequest httpRequest) {
    String userId = utilisateur(auth).id();
    jdbc.update("DELETE FROM users WHERE id = :userId", Map.of("userId", userId));
    HttpSession session = httpRequest.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
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
