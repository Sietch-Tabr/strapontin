/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;
import static fr.strapontin.web.ControllerUtils.utilisateur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
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
 * Contrôleur REST d'administration — HTTP pur, délègue à {@link
 * AdminService}, accessible aux seuls utilisateurs {@code ADMIN}.
 * @since 2.0.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private static final String CLE_SUCCES = "succes";
  private static final String CLE_ACTION = "action";
  private static final String MSG_UTILISATEUR_INTROUVABLE = "Utilisateur introuvable.";

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
   * Crée un utilisateur et envoie une invitation — le lien n'est exposé
   * dans la réponse que si aucun service email n'est disponible.
   * @param requete corps JSON avec l'email
   * @param auth contexte d'authentification admin
   * @return 201 (identifiant + lien si email indisponible), 400 email
   *     pris, 503 PUBLIC_BASE_URL non configurée
   */
  @PostMapping("/utilisateurs")
  public ResponseEntity<Map<String, Object>> creerUtilisateur(
      @Valid @RequestBody final RequeteCreerUtilisateur requete, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    final String email =
        requete.email() == null ? "" : requete.email().strip().toLowerCase(Locale.ROOT);

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
   * Envoie un email de réinitialisation — expose {@code lienReset} dans
   * la réponse si Resend indisponible (transmission manuelle à un
   * utilisateur bloqué).
   * @param userId identifiant de l'utilisateur
   * @param auth contexte d'authentification admin
   * @return 200 si envoyé, 400/404 en cas d'erreur, 503 si non configurée
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
        .orElseGet(() -> erreur(404, MSG_UTILISATEUR_INTROUVABLE));
  }

  /**
   * Initie la révocation différée du rôle admin (effective dans 7 jours).
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param auth contexte d'authentification admin
   * @return 200 si initiée, 400 si déjà user, 404 si introuvable, 409 si seul
   *     admin
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
   * @return 200 si annulée, 400 si auto-annulation ou aucune révocation en
   *     cours
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
   * @return 200 si réinitialisé, 400 si compte admin courant, 404 si
   *     introuvable
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
    return erreur(404, MSG_UTILISATEUR_INTROUVABLE);
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
    return erreur(404, MSG_UTILISATEUR_INTROUVABLE);
  }

  /**
   * Verrouille immédiatement un compte suspecté compromis (bloque la
   * connexion, révoque les sessions actives).
   *
   * @param userId identifiant de l'utilisateur cible
   * @param auth contexte d'authentification admin
   * @return 200 si verrouillé, 400 si auto-verrouillage, 404 si introuvable
   */
  @PostMapping("/utilisateurs/{userId}/verrouiller")
  public ResponseEntity<Map<String, Object>> verrouillerCompte(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de verrouiller votre propre compte.");
    }
    if (adminService.verrouillerCompte(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of(CLE_SUCCES, true, CLE_ACTION, "verrouillerCompte"));
    }
    return erreur(404, MSG_UTILISATEUR_INTROUVABLE);
  }

  /**
   * Déverrouille un compte précédemment verrouillé.
   *
   * @param userId identifiant de l'utilisateur cible
   * @param auth contexte d'authentification admin
   * @return 200 si déverrouillé, 404 si introuvable
   */
  @DeleteMapping("/utilisateurs/{userId}/verrouiller")
  public ResponseEntity<Map<String, Object>> deverrouillerCompte(
      @PathVariable final String userId, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    if (adminService.deverrouillerCompte(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of(CLE_SUCCES, true, CLE_ACTION, "deverrouillerCompte"));
    }
    return erreur(404, MSG_UTILISATEUR_INTROUVABLE);
  }

  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
