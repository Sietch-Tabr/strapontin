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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/// Contrôleur REST d'administration.
///
/// <p>HTTP pur — délègue toute la logique métier et l'accès aux données à {@link AdminService}.
/// Accessible uniquement aux utilisateurs avec le rôle {@code ADMIN}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private final AdminService adminService;
  private final ConfigAdminService configAdminService;
  private final JobsAdminService jobsAdminService;
  private final BanniereService banniereService;
  private final WorkerProxyService workerProxyService;
  private final EmailService emailService;
  private final VapidService vapidService;
  private final ConfigService configService;

  /// @param adminService service d'administration (gestion utilisateurs)
  /// @param configAdminService service de configuration applicative
  /// @param jobsAdminService service de statistiques des jobs Quartz
  /// @param banniereService service de gestion de la bannière système
  /// @param workerProxyService proxy HTTP vers le worker (relance des fetches)
  /// @param emailService service d'envoi d'emails
  /// @param vapidService service de gestion des clés VAPID
  /// @param configService service de configuration pour lire PUBLIC_BASE_URL
  public AdminController(
      AdminService adminService,
      ConfigAdminService configAdminService,
      JobsAdminService jobsAdminService,
      BanniereService banniereService,
      WorkerProxyService workerProxyService,
      EmailService emailService,
      VapidService vapidService,
      ConfigService configService) {
    this.adminService = adminService;
    this.configAdminService = configAdminService;
    this.jobsAdminService = jobsAdminService;
    this.banniereService = banniereService;
    this.workerProxyService = workerProxyService;
    this.emailService = emailService;
    this.vapidService = vapidService;
    this.configService = configService;
  }

  /// Retourne une page d'utilisateurs avec leur dernière activité.
  ///
  /// @param page numéro de page (0-indexé, défaut 0)
  /// @return la page d'utilisateurs triés par date de création
  @GetMapping("/utilisateurs")
  public ResponseEntity<ReponseUtilisateursAdmin> listerUtilisateurs(
      @RequestParam(defaultValue = "0") int page) {
    return ResponseEntity.ok(adminService.listerUtilisateurs(page));
  }

  /// Crée un nouveau compte utilisateur et envoie un email d'invitation.
  ///
  /// <p>Le lien d'invitation n'est <strong>pas</strong> renvoyé dans la réponse quand l'email est
  /// configuré (le token ne doit pas transiter côté client). En revanche, si aucun service email
  /// n'est disponible ({@code RESEND_API_KEY} absent), le lien est exposé dans la réponse comme
  /// seul canal de livraison possible : l'admin doit alors le transmettre manuellement.
  ///
  /// @param requete corps JSON avec l'email
  /// @param auth contexte d'authentification admin
  /// @return 201 avec l'identifiant temporaire (et le lien si email indisponible), 400 si l'email
  ///   est déjà pris, 503 si {@code PUBLIC_BASE_URL} n'est pas configurée
  @PostMapping("/utilisateurs")
  public ResponseEntity<Map<String, Object>> creerUtilisateur(
      @Valid @RequestBody RequeteCreerUtilisateur requete, Authentication auth) {
    String adminId = utilisateur(auth).id();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();

    if (email.isEmpty()) {
      return erreur(400, "Format d'email invalide.");
    }

    Optional<String> baseUrl = resoudreBaseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, "PUBLIC_BASE_URL n'est pas configurée. Impossible d'inviter un compte.");
    }

    boolean emailDisponible = emailService.estDisponible();
    return adminService
        .creerUtilisateur(email, adminId, baseUrl.get())
        .map(
            r -> {
              Map<String, Object> corps = new HashMap<>();
              corps.put("succes", true);
              corps.put("email", r.email());
              corps.put("identifiant", r.identifiant());
              if (!emailDisponible) {
                corps.put("lienReset", r.lienReset());
              }
              return ResponseEntity.status(201).<Map<String, Object>>body(corps);
            })
        .orElseGet(() -> erreur(400, "Impossible de créer le compte. Email déjà utilisé ?"));
  }

  /// Modifie le nom et/ou l'email d'un utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur à modifier
  /// @param requete corps JSON avec {@code nom} et/ou {@code email}
  /// @param auth contexte d'authentification admin
  /// @return 200 si modifié — 400 si invalide (via {@link GlobalExceptionHandler})
  @PatchMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> modifierUtilisateur(
      @PathVariable String userId,
      @Valid @RequestBody RequeteModifierUtilisateur requete,
      Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Utilisez /parametres pour modifier votre propre compte.");
    }
    adminService.modifierUtilisateur(userId, requete, adminId);
    return ResponseEntity.ok(Map.of("succes", true, "action", "modifierCompte"));
  }

  /// Supprime un compte utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur à supprimer
  /// @param auth contexte d'authentification admin
  /// @return 204 si supprimé, 400 si tentative d'auto-suppression
  @DeleteMapping("/utilisateurs/{userId}")
  public ResponseEntity<Map<String, Object>> supprimerUtilisateur(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de supprimer ce compte.");
    }
    adminService.supprimerUtilisateur(userId, adminId);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Envoie un email de réinitialisation de mot de passe à un utilisateur.
  ///
  /// <p>Si Resend n'est pas disponible, inclut {@code lienReset} dans la réponse — comme {@link
  /// #creerUtilisateur} — pour que l'admin puisse transmettre le lien manuellement à un utilisateur
  /// bloqué, plutôt que de n'avoir aucun moyen de récupération.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param auth contexte d'authentification admin
  /// @return 200 si envoyé, 400/404 en cas d'erreur, 503 si {@code PUBLIC_BASE_URL} n'est pas
  ///   configurée
  @PostMapping("/utilisateurs/{userId}/reset-mdp")
  public ResponseEntity<Map<String, Object>> reinitialiserMdp(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }
    Optional<String> baseUrl = resoudreBaseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, "PUBLIC_BASE_URL n'est pas configurée. Impossible d'envoyer le lien.");
    }
    boolean emailDisponible = emailService.estDisponible();
    return adminService
        .reinitialiserMdp(userId, adminId, baseUrl.get())
        .map(
            r -> {
              Map<String, Object> corps = new HashMap<>();
              corps.put("succes", true);
              corps.put("action", "reinitialiserMdp");
              corps.put("email", r.email());
              if (!emailDisponible) {
                corps.put("lienReset", r.lienReset());
              }
              return ResponseEntity.ok(corps);
            })
        .orElseGet(() -> erreur(404, "Utilisateur introuvable."));
  }

  /// Initie la révocation différée du rôle admin (effective dans 7 jours).
  ///
  /// @param userId identifiant de l'administrateur à révoquer
  /// @param auth contexte d'authentification admin
  /// @return 200 si initiée, 400 si déjà user, 404 si introuvable, 409 si seul admin
  @PostMapping("/utilisateurs/{userId}/revoquer-admin")
  public ResponseEntity<Map<String, Object>> initierRevocationAdmin(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de révoquer votre propre compte.");
    }
    adminService.initierRevocationAdmin(userId, adminId);
    return ResponseEntity.ok(Map.of("succes", true, "action", "initierRevocationAdmin"));
  }

  /// Annule une révocation admin en cours.
  ///
  /// @param userId identifiant de l'administrateur concerné
  /// @param auth contexte d'authentification admin
  /// @return 200 si annulée, 400 si auto-annulation ou aucune révocation en cours
  @DeleteMapping("/utilisateurs/{userId}/revoquer-admin")
  public ResponseEntity<Map<String, Object>> annulerRevocationAdmin(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible d'annuler votre propre révocation.");
    }
    adminService.annulerRevocationAdmin(userId, adminId);
    return ResponseEntity.ok(Map.of("succes", true, "action", "annulerRevocationAdmin"));
  }

  /// Réinitialise la 2FA (TOTP) d'un utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param auth contexte d'authentification admin
  /// @return 200 si réinitialisé, 400 si compte admin courant, 404 si introuvable
  @PostMapping("/utilisateurs/{userId}/reset-totp")
  public ResponseEntity<Map<String, Object>> reinitialiserTotp(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }
    if (adminService.reinitialiserTotp(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of("succes", true, "action", "reinitialiserTotp"));
    }
    return erreur(404, "Utilisateur introuvable.");
  }

  /// Révoque immédiatement toutes les sessions actives d'un utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur cible
  /// @param auth contexte d'authentification admin
  /// @return 200 si révoqué, 404 si introuvable
  @PostMapping("/utilisateurs/{userId}/revoquer-sessions")
  public ResponseEntity<Map<String, Object>> revoquerSessions(
      @PathVariable String userId, Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (adminService.revoquerToutesSessions(userId, adminId)) {
      return ResponseEntity.ok(
          Map.<String, Object>of("succes", true, "action", "revoquerSessions"));
    }
    return erreur(404, "Utilisateur introuvable.");
  }

  /// Retourne le statut des clés de configuration.
  ///
  /// @return la liste des statuts de configuration
  @GetMapping("/config")
  public ResponseEntity<List<ConfigStatut>> lireConfigStatuts() {
    return ResponseEntity.ok(configAdminService.lireConfigStatuts());
  }

  /// Sauvegarde une valeur de configuration applicative.
  ///
  /// @param requete corps JSON avec {@code cle} et {@code valeur}
  /// @param auth contexte d'authentification admin
  /// @return 200 si sauvegardé — 400 si invalide (via {@link GlobalExceptionHandler})
  @PatchMapping("/config")
  public ResponseEntity<Map<String, Object>> sauvegarderConfig(
      @Valid @RequestBody RequeteConfig requete, Authentication auth) {
    String adminId = utilisateur(auth).id();
    String cle = requete.cle() == null ? "" : requete.cle().strip();
    String valeur = requete.valeur() == null ? "" : requete.valeur().strip();

    configAdminService.sauvegarderConfig(cle, valeur, adminId);
    return ResponseEntity.ok(Map.of("succes", true, "action", "sauvegarderConfig", "cle", cle));
  }

  /// Génère une nouvelle paire de clés VAPID EC P-256.
  ///
  /// @return 200 avec la clé publique générée et le nombre d'abonnements push invalidés
  @PostMapping("/vapid/generer")
  public ResponseEntity<Map<String, Object>> genererVapid() {
    try {
      VapidService.ResultatVapid resultat = vapidService.genererVapid();
      return ResponseEntity.ok(
          Map.of(
              "succes",
              true,
              "action",
              "genererVapid",
              "clePublique",
              resultat.clePublique(),
              "nbAbonnementsSupprimes",
              resultat.nbAbonnementsSupprimes()));
    } catch (Exception e) {
      log.error("Erreur génération clés VAPID", e);
      return erreur(500, "Erreur lors de la génération des clés VAPID.");
    }
  }

  /// Teste la validité de la clé Ticketmaster API via un appel minimal.
  ///
  /// @return 200 si la clé est valide, 400 sinon
  @PostMapping("/config/tester-tm")
  public ResponseEntity<Map<String, Object>> testerApiTm() {
    boolean valide = configAdminService.testerApiTm();
    return valide
        ? ResponseEntity.ok(Map.of("valide", true))
        : erreur(400, "Clé Ticketmaster invalide ou absente.");
  }

  /// Envoie un email de test pour vérifier la configuration Resend.
  ///
  /// @param auth contexte d'authentification admin
  /// @return 200 si l'email a été envoyé, 400 sinon
  @PostMapping("/config/tester-email")
  public ResponseEntity<Map<String, Object>> testerEmail(Authentication auth) {
    String emailAdmin = utilisateur(auth).email();
    boolean succes = emailService.envoyerEmailTest(emailAdmin);
    return succes
        ? ResponseEntity.ok(Map.of("succes", true))
        : erreur(400, "Envoi impossible — vérifiez RESEND_API_KEY et CONTACT_EMAIL.");
  }

  /// Publie un message de bannière système.
  ///
  /// @param requete corps JSON avec {@code message} et {@code type}
  /// @return 200 si publié
  @PostMapping("/banniere")
  public ResponseEntity<Map<String, Object>> publierBanniere(
      @Valid @RequestBody RequeteBanniere requete) {
    String type =
        Set.of("info", "warning", "error").contains(requete.type()) ? requete.type() : "info";
    banniereService.publierBanniere(requete.message().strip(), type);
    return ResponseEntity.ok(Map.of("succes", true, "action", "publierBanniere"));
  }

  /// Efface la bannière système.
  ///
  /// @return 200 si effacée
  @DeleteMapping("/banniere")
  public ResponseEntity<Map<String, Object>> effacerBanniere() {
    banniereService.effacerBanniere();
    return ResponseEntity.ok(Map.of("succes", true, "action", "effacerBanniere"));
  }

  /// Retourne les 200 derniers logs admin.
  ///
  /// @return la liste des entrées de log
  @GetMapping("/logs")
  public ResponseEntity<List<LogAdminService.EntreeLogAdmin>> lireLogs() {
    return ResponseEntity.ok(adminService.lireLogsAdmin());
  }

  /// Retourne les statistiques des jobs Quartz et le quota TM.
  ///
  /// @return les stats de jobs et le quota TM
  @GetMapping("/jobs")
  public ResponseEntity<Map<String, Object>> lireJobs() {
    return ResponseEntity.ok(jobsAdminService.lireJobs());
  }

  /// Déclenche immédiatement les fetches via le worker.
  ///
  /// @return 200 si déclenché, 502 en cas d'erreur interne worker, 503 si injoignable
  @PostMapping("/relancer")
  public ResponseEntity<Map<String, Object>> relancerFetches() {
    ResultatRelance resultat = workerProxyService.relancerFetches();
    if (resultat.succes()) {
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    }
    return ResponseEntity.status(resultat.statutHttp())
        .body(Map.of("erreur", resultat.messageErreur()));
  }

  /// Déclenche immédiatement un job de maintenance identifié par son {@code typeJob} lisible
  /// (v2.6.0) — bouton « Relancer » par job dans l'onglet admin Tableau de bord.
  ///
  /// <p>Le code HTTP renvoyé distingue désormais le job inconnu (400) de l'erreur interne worker
  /// (502) et du worker injoignable (503) — auparavant les trois étaient masqués sous un 503
  /// générique, ce qui égarait le diagnostic d'un admin.
  ///
  /// @param typeJob identifiant lisible du job (ex. {@code "purger:salles"})
  /// @return 200 si déclenché, 400 si le job est inconnu, 502/503 selon la cause de l'échec
  @PostMapping("/jobs/relancer")
  public ResponseEntity<Map<String, Object>> relancerJob(@RequestParam String typeJob) {
    ResultatRelance resultat = workerProxyService.relancerJob(typeJob);
    if (resultat.succes()) {
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    }
    return ResponseEntity.status(resultat.statutHttp())
        .body(Map.of("erreur", resultat.messageErreur()));
  }

  /// Retourne les informations système non liées à la configuration chiffrée.
  ///
  /// @return map avec {@code appConfigSecretPresente}
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> lireInfo() {
    return ResponseEntity.ok(
        Map.of("appConfigSecretPresente", System.getenv("APP_CONFIG_SECRET") != null));
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }

  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
