/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /// @param adminService service d'administration
  public AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  /// Retourne la liste des utilisateurs avec leur dernière activité.
  ///
  /// @return les utilisateurs triés par date de création
  @GetMapping("/utilisateurs")
  public ResponseEntity<List<UtilisateurAdmin>> listerUtilisateurs() {
    return ResponseEntity.ok(adminService.listerUtilisateurs());
  }

  /// Crée un nouveau compte utilisateur et envoie un email d'invitation.
  ///
  /// @param requete corps JSON avec l'email
  /// @param auth    contexte d'authentification admin
  /// @param request la requête HTTP (pour construire le lien d'invitation)
  /// @return 201 avec l'identifiant temporaire, ou 400 si l'email est déjà pris
  @PostMapping("/utilisateurs")
  public ResponseEntity<Map<String, Object>> creerUtilisateur(
      @Valid @RequestBody RequeteCreerUtilisateur requete,
      Authentication auth,
      HttpServletRequest request) {
    String adminId = utilisateur(auth).id();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();

    if (email.isEmpty()) {
      return erreur(400, "Format d'email invalide.");
    }

    return adminService
        .creerUtilisateur(email, adminId, baseUrlDepuisRequete(request))
        .map(
            r ->
                ResponseEntity.status(201)
                    .<Map<String, Object>>body(
                        Map.of(
                            "succes", true,
                            "email", r.email(),
                            "identifiant", r.identifiant(),
                            "lienReset", r.lienReset())))
        .orElseGet(() -> erreur(400, "Impossible de créer le compte. Email déjà utilisé ?"));
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
      @Valid @RequestBody RequeteModifierUtilisateur requete,
      Authentication auth) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Utilisez /parametres pour modifier votre propre compte.");
    }
    String erreur = adminService.modifierUtilisateur(userId, requete, adminId);
    if (erreur != null) return erreur(400, erreur);
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
    adminService.supprimerUtilisateur(userId, adminId);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Envoie un email de réinitialisation de mot de passe à un utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param auth    contexte d'authentification admin
  /// @param request la requête HTTP
  /// @return 200 si envoyé, 400/404 en cas d'erreur
  @PostMapping("/utilisateurs/{userId}/reset-mdp")
  public ResponseEntity<Map<String, Object>> reinitialiserMdp(
      @PathVariable String userId, Authentication auth, HttpServletRequest request) {
    String adminId = utilisateur(auth).id();
    if (userId.equals(adminId)) {
      return erreur(400, "Impossible de réinitialiser ce compte.");
    }
    return adminService
        .reinitialiserMdp(userId, adminId, baseUrlDepuisRequete(request))
        .map(
            email ->
                ResponseEntity.ok(
                    Map.<String, Object>of(
                        "succes", true, "action", "reinitialiserMdp", "email", email)))
        .orElseGet(() -> erreur(404, "Utilisateur introuvable."));
  }

  /// Retourne le statut des clés de configuration.
  ///
  /// @return la liste des statuts de configuration
  @GetMapping("/config")
  public ResponseEntity<List<ConfigStatut>> lireConfigStatuts() {
    return ResponseEntity.ok(adminService.lireConfigStatuts());
  }

  /// Sauvegarde une valeur de configuration applicative.
  ///
  /// @param requete corps JSON avec {@code cle} et {@code valeur}
  /// @param auth    contexte d'authentification admin
  /// @return 200 si sauvegardé, 400 si clé non autorisée ou format invalide
  @PatchMapping("/config")
  public ResponseEntity<Map<String, Object>> sauvegarderConfig(
      @Valid @RequestBody RequeteConfig requete, Authentication auth) {
    String adminId = utilisateur(auth).id();
    String cle = requete.cle() == null ? "" : requete.cle().strip();
    String valeur = requete.valeur() == null ? "" : requete.valeur().strip();

    String erreur = adminService.sauvegarderConfig(cle, valeur, adminId);
    if (erreur != null) return erreur(400, erreur);
    return ResponseEntity.ok(Map.of("succes", true, "action", "sauvegarderConfig", "cle", cle));
  }

  /// Génère une nouvelle paire de clés VAPID EC P-256.
  ///
  /// @return 200 si générées
  @PostMapping("/vapid/generer")
  public ResponseEntity<Map<String, Object>> genererVapid() {
    try {
      adminService.genererVapid();
      return ResponseEntity.ok(Map.of("succes", true, "action", "genererVapid"));
    } catch (Exception e) {
      log.error("Erreur génération clés VAPID", e);
      return erreur(500, "Erreur lors de la génération des clés VAPID.");
    }
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
    adminService.publierBanniere(requete.message().strip(), type);
    return ResponseEntity.ok(Map.of("succes", true, "action", "publierBanniere"));
  }

  /// Efface la bannière système.
  ///
  /// @return 200 si effacée
  @DeleteMapping("/banniere")
  public ResponseEntity<Map<String, Object>> effacerBanniere() {
    adminService.effacerBanniere();
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
    return ResponseEntity.ok(adminService.lireJobs());
  }

  /// Déclenche immédiatement les fetches via le worker.
  ///
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  @PostMapping("/relancer")
  public ResponseEntity<Map<String, Object>> relancerFetches() {
    return adminService.relancerFetches();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }

  private static String baseUrlDepuisRequete(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
      return scheme + "://" + host + request.getContextPath();
    }
    return scheme + "://" + host + ":" + port + request.getContextPath();
  }
}
