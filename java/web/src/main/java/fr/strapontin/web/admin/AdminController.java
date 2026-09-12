/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST d'administration.
 *
 * <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link AdminService}.
 * Accessible uniquement aux utilisateurs avec le rôle {@code ADMIN}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private static final String CLE_SUCCES = "succes";
  private static final String CLE_ACTION = "action";

  private final AdminService adminService;
  private final AdminRevocationService adminRevocationService;
  private final EmailService emailService;
  private final ConfigService configService;

  /**
   * @param adminService service d'administration (gestion utilisateurs)
   * @param adminRevocationService révocation différée du rôle admin
   * @param emailService service d'envoi d'emails
   * @param configService service de configuration pour lire PUBLIC_BASE_URL
   */
  public AdminController(
      final AdminService adminService,
      final AdminRevocationService adminRevocationService,
      final EmailService emailService,
      final ConfigService configService) {
    this.adminService = adminService;
    this.adminRevocationService = adminRevocationService;
    this.emailService = emailService;
    this.configService = configService;
  }

  /**
   * Retourne une page d'utilisateurs avec leur dernière activité.
   *
   * @param page numéro de page (0-indexé, défaut 0)
   * @return la page d'utilisateurs triés par date de création
   */
  @GetMapping("/utilisateurs")
  public ResponseEntity<ReponseUtilisateursAdmin> listerUtilisateurs(
      @RequestParam(defaultValue = "0") final int page) {
    return ResponseEntity.ok(adminService.listerUtilisateurs(page));
  }

  /**
   * Crée un nouveau compte utilisateur et envoie un email d'invitation.
   *
   * <p>Le lien d'invitation n'est <strong>pas</strong> renvoyé dans la réponse quand l'email est
   * configuré (le token ne doit pas transiter côté client). En revanche, si aucun service email
   * n'est disponible ({@code RESEND_API_KEY} absent), le lien est exposé dans la réponse comme seul
   * canal de livraison possible : l'admin doit alors le transmettre manuellement.
   *
   * @param requete corps JSON avec l'email
   * @param auth contexte d'authentification admin
   * @return 201 avec l'identifiant temporaire (et le lien si email indisponible), 400 si l'email
   *     est déjà pris, 503 si {@code PUBLIC_BASE_URL} n'est pas configurée
   */
  @PostMapping("/utilisateurs")
  public ResponseEntity<Map<String, Object>> creerUtilisateur(
      @Valid @RequestBody final RequeteCreerUtilisateur requete, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    final String email = requete.email() == null ? "" : requete.email().strip().toLowerCase(Locale.ROOT);

    if (email.isEmpty()) {
      return erreur(400, "Format d'email invalide.");
    }

    final Optional<String> baseUrl = resoudreBaseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, "PUBLIC_BASE_URL n'est pas configurée. Impossible d'inviter un compte.");
    }

    final boolean emailDisponible = emailService.estDisponible();
    return adminService
        .creerUtilisateur(email, adminId, baseUrl.get())
        .map(
            r -> {
              final Map<String, Object> corps = new HashMap<>();
              corps.put(CLE_SUCCES, true);
              corps.put("email", r.email());
              corps.put("identifiant", r.identifiant());
              if (!emailDisponible) {
                corps.put("lienReset", r.lienReset());
              }
              return ResponseEntity.status(201).<Map<String, Object>>body(corps);
            })
        .orElseGet(() -> erreur(400, "Impossible de créer le compte. Email déjà utilisé ?"));
  }

  /**
   * Modifie le nom et/ou l'email d'un utilisateur.
   *
   * @param userId identifiant de l'utilisateur à modifier
   * @param requete corps JSON avec {@code nom} et/ou {@code email}
   * @param auth contexte d'authentification admin
   * @return 200 si modifié — 400 si invalide (via {@link GlobalExceptionHandler})
   */
  @PatchMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> modifierUtilisateur(
      @PathVariable final String userId,
      @Valid @RequestBody final RequeteModifierUtilisateur requete,
      final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Utilisez /parametres pour modifier votre propre compte.");
    }
    adminService.modifierUtilisateur(userId, requete, adminId);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "modifierCompte"));
  }

  /**
   * Supprime un compte utilisateur.
   *
   * @param userId identifiant de l'utilisateur à supprimer
   * @param auth contexte d'authentification admin
   * @return 204 si supprimé, 400 si tentative d'auto-suppression
   */
  @DeleteMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> supprimerUtilisateur(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de supprimer ce compte.");
    }
    adminService.supprimerUtilisateur(userId, adminId);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /**
   * Envoie un email de réinitialisation de mot de passe à un utilisateur.
   *
   * <p>Si Resend n'est pas disponible, inclut {@code lienReset} dans la réponse — comme {@link
   * #creerUtilisateur} — pour que l'admin puisse transmettre le lien manuellement à un utilisateur
   * bloqué, plutôt que de n'avoir aucun moyen de récupération.
   *
   * @param userId identifiant de l'utilisateur
   * @param auth contexte d'authentification admin
   * @return 200 si envoyé, 400/404 en cas d'erreur, 503 si {@code PUBLIC_BASE_URL} n'est pas
   *     configurée
   */
  @PostMapping("/utilisateurs/{userId}/reset-mdp")
  public ResponseEntity<Map<String, Object>> reinitialiserMdp(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }
    final Optional<String> baseUrl = resoudreBaseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, "PUBLIC_BASE_URL n'est pas configurée. Impossible d'envoyer le lien.");
    }
    final boolean emailDisponible = emailService.estDisponible();
    return adminService
        .reinitialiserMdp(userId, adminId, baseUrl.get())
        .map(
            r -> {
              final Map<String, Object> corps = new HashMap<>();
              corps.put(CLE_SUCCES, true);
              corps.put(CLE_ACTION, "reinitialiserMdp");
              corps.put("email", r.email());
              if (!emailDisponible) {
                corps.put("lienReset", r.lienReset());
              }
              return ResponseEntity.ok(corps);
            })
        .orElseGet(() -> erreur(404, "Utilisateur introuvable."));
  }

  /**
   * Initie la révocation différée du rôle admin (effective dans 7 jours).
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param auth contexte d'authentification admin
   * @return 200 si initiée, 400 si déjà user, 404 si introuvable, 409 si seul admin
   */
  @PostMapping("/utilisateurs/{userId}/revoquer-admin")
  public ResponseEntity<Map<String, Object>> initierRevocationAdmin(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de révoquer votre propre compte.");
    }
    adminRevocationService.initierRevocationAdmin(userId, adminId);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "initierRevocationAdmin"));
  }

  /**
   * Annule une révocation admin en cours.
   *
   * @param userId identifiant de l'administrateur concerné
   * @param auth contexte d'authentification admin
   * @return 200 si annulée, 400 si auto-annulation ou aucune révocation en cours
   */
  @DeleteMapping("/utilisateurs/{userId}/revoquer-admin")
  public ResponseEntity<Map<String, Object>> annulerRevocationAdmin(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible d'annuler votre propre révocation.");
    }
    adminRevocationService.annulerRevocationAdmin(userId, adminId);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "annulerRevocationAdmin"));
  }

  /**
   * Réinitialise la 2FA (TOTP) d'un utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @param auth contexte d'authentification admin
   * @return 200 si réinitialisé, 400 si compte admin courant, 404 si introuvable
   */
  @PostMapping("/utilisateurs/{userId}/reset-totp")
  public ResponseEntity<Map<String, Object>> reinitialiserTotp(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }
    if (adminService.reinitialiserTotp(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of(CLE_SUCCES, true, CLE_ACTION, "reinitialiserTotp"));
    }
    return erreur(404, "Utilisateur introuvable.");
  }

  /**
   * Révoque immédiatement toutes les sessions actives d'un utilisateur.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param auth contexte d'authentification admin
   * @return 200 si révoqué, 404 si introuvable
   */
  @PostMapping("/utilisateurs/{userId}/revoquer-sessions")
  public ResponseEntity<Map<String, Object>> revoquerSessions(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (adminService.revoquerToutesSessions(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of(CLE_SUCCES, true, CLE_ACTION, "revoquerSessions"));
    }
    return erreur(404, "Utilisateur introuvable.");
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }

  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
