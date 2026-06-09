/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
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
/// <p>HTTP pur — délègue l'accès aux données à {@link UtilisateurRepository}.
/// Gère la lecture/mise à jour du profil, le mot de passe et la suppression de compte.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/user")
public class UserController {

  private static final Pattern FORMAT_EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
  private static final Pattern FORMAT_IDENTIFIANT = Pattern.compile("^[a-zA-Z0-9_]+$");

  private final UtilisateurRepository utilisateurRepository;
  private final PasswordEncoder passwordEncoder;

  /// @param utilisateurRepository accès aux données utilisateur
  /// @param passwordEncoder        encodeur bcrypt
  public UserController(
      UtilisateurRepository utilisateurRepository, PasswordEncoder passwordEncoder) {
    this.utilisateurRepository = utilisateurRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /// Retourne le profil de l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return le profil (id, email, identifiant, pendingEmail)
  @GetMapping("/profile")
  public ResponseEntity<ProfilUtilisateur> lireProfil(Authentication auth) {
    UtilisateurDetails details = utilisateur(auth);
    return utilisateurRepository
        .lireEmails(details.id())
        .map(
            e ->
                new ProfilUtilisateur(
                    details.id(),
                    e.email(),
                    details.identifiant(),
                    e.pendingEmail(),
                    details.twoFactorActif()))
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /// Met à jour l'email de l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code email}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si invalide
  @PatchMapping("/profile")
  public ResponseEntity<Map<String, Object>> majProfil(
      @Valid @RequestBody RequeteProfil requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (requete.email() != null) {
      String email = requete.email().strip().toLowerCase();
      if (!FORMAT_EMAIL.matcher(email).matches() || email.length() > 254) {
        return erreur(400, "Format d'email invalide.");
      }
      if (utilisateurRepository.emailDejaUtilise(email, userId)) {
        return erreur(400, "Cette adresse email est déjà utilisée.");
      }
      utilisateurRepository.majEmail(userId, email);
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
    if (utilisateurRepository.identifiantDejaUtilise(identifiant, userId)) {
      return erreur(400, "Cet identifiant est déjà utilisé.");
    }

    utilisateurRepository.majIdentifiant(userId, identifiant);
    return ResponseEntity.ok(Map.of("succes", "identifiant"));
  }

  /// Change le mot de passe de l'utilisateur courant et invalide la session.
  ///
  /// @param requete  corps JSON avec {@code motDePasseActuel} et {@code motDePasseNouveau}
  /// @param auth     contexte d'authentification
  /// @param request  la requête HTTP (pour invalider la session)
  /// @return 200 si changé, 400 si invalide ou mot de passe actuel incorrect
  @PostMapping("/password")
  public ResponseEntity<Map<String, Object>> changerMotDePasse(
      @Valid @RequestBody RequeteMotDePasse requete,
      Authentication auth,
      HttpServletRequest request) {
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

    utilisateurRepository.changerMotDePasse(
        details.id(), passwordEncoder.encode(requete.motDePasseNouveau()));

    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();

    return ResponseEntity.ok(Map.of("succes", "motDePasse"));
  }

  /// Active le TOTP pour l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param secret le secret Base32 à persister
  public void activerTotp(String userId, String secret) {
    utilisateurRepository.activerTotp(userId, secret);
  }

  /// Retourne le secret TOTP stocké.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return le secret Base32, ou {@code null} si absent
  public String lireSecretTotp(String userId) {
    return utilisateurRepository.lireSecretTotp(userId);
  }

  /// Désactive le TOTP.
  ///
  /// @param userId identifiant de l'utilisateur
  public void desactiverTotp(String userId) {
    utilisateurRepository.desactiverTotp(userId);
  }

  /// Supprime le compte de l'utilisateur courant (cascade sur toutes ses données).
  ///
  /// @param auth       contexte d'authentification
  /// @param httpRequest pour invalider la session
  /// @return 204 si supprimé
  @DeleteMapping
  public ResponseEntity<Void> supprimerCompte(Authentication auth, HttpServletRequest httpRequest) {
    String userId = utilisateur(auth).id();
    utilisateurRepository.supprimer(userId);
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
