/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour le profil utilisateur courant.
 *
 * <p>HTTP pur — délègue l'accès aux données à {@link UtilisateurRepository}. Gère la lecture du
 * profil, l'identifiant, le mot de passe et la suppression de compte.
 *
 * <p>La modification d'email n'est volontairement pas exposée ici : le seul chemin autorisé est le
 * flux à confirmation par token {@code POST /api/parametres/email-change} ({@link
 * ParametresRestController}), qui vérifie la possession de la nouvelle adresse avant de
 * l'appliquer — contrairement à un simple {@code PATCH} qui l'aurait acceptée immédiatement.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

  private final UtilisateurRepository utilisateurRepository;
  private final PasswordEncoder passwordEncoder;
  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

  /**
   * @param utilisateurRepository accès aux données utilisateur
   * @param passwordEncoder encodeur bcrypt
   * @param sessionRepository dépôt de sessions JDBC (révocation multi-appareils)
   */
  public UserController(
      final UtilisateurRepository utilisateurRepository,
      final PasswordEncoder passwordEncoder,
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    this.utilisateurRepository = utilisateurRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessionRepository = sessionRepository;
  }

  /**
   * Retourne le profil de l'utilisateur courant.
   *
   * @param auth contexte d'authentification
   * @return le profil (id, email, identifiant, pendingEmail)
   */
  @GetMapping("/profile")
  public ResponseEntity<ProfilUtilisateur> lireProfil(final Authentication auth) {
    final UtilisateurDetails details = utilisateur(auth);
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

  /**
   * Met à jour l'identifiant (username) de l'utilisateur courant.
   *
   * @param requete corps JSON avec {@code identifiant}
   * @param auth contexte d'authentification
   * @return 200 si mis à jour, 400 si invalide ou déjà utilisé
   */
  @PatchMapping("/identifiant")
  public ResponseEntity<Map<String, Object>> majIdentifiant(
      @Valid @RequestBody final RequeteIdentifiant requete, final Authentication auth) {
    final String userId = utilisateur(auth).id();
    final String identifiant = requete.identifiant().strip().toLowerCase();

    if (utilisateurRepository.identifiantDejaUtilise(identifiant, userId)) {
      return erreur(400, "Cet identifiant est déjà utilisé.");
    }

    utilisateurRepository.majIdentifiant(userId, identifiant);
    return ResponseEntity.ok(Map.of("succes", "identifiant"));
  }

  /**
   * Change le mot de passe de l'utilisateur courant et révoque toutes ses sessions actives.
   *
   * <p>Révoque les sessions sur tous les appareils, pas seulement celle de la requête en cours —
   * sinon une session compromise sur un autre appareil survivrait jusqu'à expiration naturelle (30
   * jours) malgré le changement de mot de passe.
   *
   * @param requete corps JSON avec {@code motDePasseActuel} et {@code motDePasseNouveau}
   * @param auth contexte d'authentification
   * @return 200 si changé, 400 si invalide ou mot de passe actuel incorrect
   */
  @PostMapping("/password")
  public ResponseEntity<Map<String, Object>> changerMotDePasse(
      @Valid @RequestBody final RequeteMotDePasse requete, final Authentication auth) {
    final UtilisateurDetails details = utilisateur(auth);

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

    sessionRepository
        .findByPrincipalName(details.identifiant())
        .keySet()
        .forEach(sessionRepository::deleteById);
    SecurityContextHolder.clearContext();

    return ResponseEntity.ok(Map.of("succes", "motDePasse"));
  }

  /**
   * Active le TOTP pour l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @param secret le secret Base32 à persister
   */
  public void activerTotp(final String userId, final String secret) {
    utilisateurRepository.activerTotp(userId, secret);
  }

  /**
   * Retourne le secret TOTP stocké.
   *
   * @param userId identifiant de l'utilisateur
   * @return le secret Base32, ou {@code null} si absent
   */
  public String lireSecretTotp(final String userId) {
    return utilisateurRepository.lireSecretTotp(userId);
  }

  /**
   * Désactive le TOTP.
   *
   * @param userId identifiant de l'utilisateur
   */
  public void desactiverTotp(final String userId) {
    utilisateurRepository.desactiverTotp(userId);
  }

  /**
   * Retourne le nombre d'échecs TOTP consécutifs de l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return nombre d'échecs
   */
  public int lireEchecsTotp(final String userId) {
    return utilisateurRepository.lireEchecsTotp(userId);
  }

  /**
   * Supprime le compte de l'utilisateur courant (cascade sur toutes ses données).
   *
   * <p>Interdit si l'utilisateur est le seul administrateur de l'instance (retourne 409).
   *
   * @param auth contexte d'authentification
   * @param httpRequest pour invalider la session
   * @return 204 si supprimé, 409 si dernier admin
   */
  @DeleteMapping
  public ResponseEntity<?> supprimerCompte(final Authentication auth, final HttpServletRequest httpRequest) {
    final UtilisateurDetails details = utilisateur(auth);
    if ("admin".equals(details.role()) && utilisateurRepository.compterAdmins() == 1) {
      return erreur(409, "Impossible de supprimer le seul compte administrateur de l'instance.");
    }
    utilisateurRepository.supprimer(details.id());
    final HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
