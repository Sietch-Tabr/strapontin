/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.LimiteurDebit;
import fr.strapontin.web.securite.RegleIdentifiant;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Duration;
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
 * Contrôleur REST pour le profil utilisateur courant — HTTP
 * pur, délègue à {@link UtilisateurRepository}. Modification
 * d'email volontairement absente (voir
 * {@link ParametresRestController}, flux à confirmation).
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

  private static final int LONGUEUR_MIN_MOT_DE_PASSE = 12;
  private static final int MAX_ECHECS_MOT_DE_PASSE = 5;
  private static final Duration FENETRE_ECHECS_MOT_DE_PASSE = Duration.ofMinutes(15);

  private final UtilisateurRepository utilisateurRepository;
  private final PasswordEncoder passwordEncoder;
  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
  private final LogAdminService logAdminService;
  // Une session déjà ouverte (volée, par exemple) ne doit pas pouvoir deviner le mot de passe
  // actuel.
  private final LimiteurDebit echecsMotDePasseActuel =
      new LimiteurDebit(
          MAX_ECHECS_MOT_DE_PASSE,
          FENETRE_ECHECS_MOT_DE_PASSE,
          FENETRE_ECHECS_MOT_DE_PASSE.plusMinutes(5),
          10_000);

  /**
   * @param utilisateurRepository accès aux données utilisateur
   * @param passwordEncoder encodeur bcrypt
   * @param sessionRepository dépôt de sessions JDBC (révocation
   *     multi-appareils)
   * @param logAdminService journalisation du blocage après échecs répétés
   */
  public UserController(
      final UtilisateurRepository utilisateurRepository,
      final PasswordEncoder passwordEncoder,
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository,
      final LogAdminService logAdminService) {
    this.utilisateurRepository = utilisateurRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessionRepository = sessionRepository;
    this.logAdminService = logAdminService;
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
    final String identifiant = RegleIdentifiant.valider(requete.identifiant());

    if (utilisateurRepository.identifiantDejaUtilise(identifiant, userId)) {
      return erreur(400, "Cet identifiant est déjà utilisé.");
    }

    utilisateurRepository.majIdentifiant(userId, identifiant);
    return ResponseEntity.ok(Map.of("succes", "identifiant"));
  }

  /**
   * Change le mot de passe et révoque toutes les sessions
   * actives (tous appareils, pas seulement la requête en cours).
   * @param requete corps JSON avec motDePasseActuel/Nouveau
   * @param auth contexte d'authentification
   * @return 200 si changé, 400 si invalide/mdp actuel incorrect, 429 après 5 mots de passe
   *     actuels incorrects en 15 minutes
   */
  @PostMapping("/password")
  public ResponseEntity<Map<String, Object>> changerMotDePasse(
      @Valid @RequestBody final RequeteMotDePasse requete, final Authentication auth) {
    final UtilisateurDetails details = utilisateur(auth);

    if (requete.motDePasseActuel() == null || requete.motDePasseNouveau() == null) {
      return erreur(400, "Tous les champs sont obligatoires.");
    }
    if (requete.motDePasseNouveau().length() < LONGUEUR_MIN_MOT_DE_PASSE) {
      return erreur(
          400,
          "Le nouveau mot de passe doit contenir au moins "
              + LONGUEUR_MIN_MOT_DE_PASSE
              + " caractères.");
    }
    if (echecsMotDePasseActuel.estEpuise(details.id())) {
      return erreur(429, "Trop de tentatives. Réessayez dans quelques minutes.");
    }
    if (!passwordEncoder.matches(requete.motDePasseActuel(), details.motDePasse())) {
      if (echecsMotDePasseActuel.enregistrer(details.id())) {
        logAdminService.logAdmin(
            "warn",
            "auth",
            "Changement de mot de passe bloqué après échecs répétés : " + details.identifiant(),
            Map.of("userId", details.id()));
      }
      return erreur(400, "Mot de passe actuel incorrect.");
    }
    echecsMotDePasseActuel.oublier(details.id());

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
   * Supprime le compte courant (cascade), interdit si seul
   * admin de l'instance (409).
   * @param auth contexte d'authentification
   * @param httpRequest pour invalider la session
   * @return 204 si supprimé, 409 si dernier admin
   */
  @DeleteMapping
  public ResponseEntity<?> supprimerCompte(
      final Authentication auth, final HttpServletRequest httpRequest) {
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
