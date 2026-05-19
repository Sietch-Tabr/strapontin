/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour la page d'administration.
///
/// Charge les quatre sources de données en parallèle et délègue toutes les mutations
/// à [AdminController]. Pattern PRG sur toutes les actions.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AdminViewController {

  private final AdminController adminController;

  private static final String CLE_ERREUR = "erreur";
  private static final String CLE_SUCCES = "succes";
  private static final String ERREUR_DEFAUT = "Erreur.";
  private static final String REDIRECT_ADMIN_UTILISATEURS = "redirect:/admin#utilisateurs";

  public AdminViewController(AdminController adminController) {
    this.adminController = adminController;
  }

  /// Affiche la page d'administration avec les utilisateurs, la configuration,
  /// les logs et les statistiques de jobs.
  ///
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template
  @GetMapping("/admin")
  public String admin(Model modele) {
    ResponseEntity<List<AdminController.UtilisateurAdmin>> repUsers =
        adminController.listerUtilisateurs();
    ResponseEntity<List<AdminController.ConfigStatut>> repConfig =
        adminController.lireConfigStatuts();
    ResponseEntity<List<LogAdminService.EntreeLogAdmin>> repLogs = adminController.lireLogs();
    ResponseEntity<Map<String, Object>> repJobs = adminController.lireJobs();

    modele.addAttribute("utilisateurs", repUsers.getBody());
    modele.addAttribute("configStatuts", repConfig.getBody());
    modele.addAttribute("logs", repLogs.getBody());
    var corpsJobs = repJobs.getBody();
    modele.addAttribute("statsJobs", corpsJobs != null ? corpsJobs.get("statsJobs") : null);
    modele.addAttribute("quotaTM", corpsJobs != null ? corpsJobs.get("quotaTM") : null);

    Instant workerLastSuccess =
        corpsJobs != null ? (Instant) corpsJobs.get("workerLastSuccess") : null;
    boolean workerEnAlerte = workerLastSuccess == null;
    String workerDepuisTexte = null;
    if (workerLastSuccess != null) {
      long minutesSince = Duration.between(workerLastSuccess, Instant.now()).toMinutes();
      workerEnAlerte = minutesSince > 180;
      if (minutesSince < 1) workerDepuisTexte = "à l'instant";
      else if (minutesSince < 60) workerDepuisTexte = "il y a " + minutesSince + " min";
      else {
        long h = minutesSince / 60;
        long m = minutesSince % 60;
        workerDepuisTexte = m > 0 ? "il y a " + h + " h " + m + " min" : "il y a " + h + " h";
      }
    }
    modele.addAttribute("workerEnAlerte", workerEnAlerte);
    modele.addAttribute("workerDepuisTexte", workerDepuisTexte);
    modele.addAttribute("currentPage", "admin");
    return "admin";
  }

  /// Crée un nouveau compte utilisateur.
  ///
  /// @param email     l'adresse email du nouvel utilisateur
  /// @param redirects attributs de redirection (lien d'invitation ou erreur)
  /// @param auth      contexte d'authentification
  /// @return redirect vers /admin
  @PostMapping("/admin/utilisateurs/creer")
  public String creerUtilisateur(
      @RequestParam String email,
      RedirectAttributes redirects,
      org.springframework.security.core.Authentication auth,
      HttpServletRequest request) {
    var requete = new AdminController.RequeteCreerUtilisateur(email);
    ResponseEntity<Map<String, Object>> rep =
        adminController.creerUtilisateur(requete, auth, request);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_SUCCES, "creerUtilisateur");
      redirects.addFlashAttribute("lienReset", corps != null ? corps.get("lienReset") : null);
      redirects.addFlashAttribute("nouvelEmail", corps != null ? corps.get("email") : null);
    }
    return REDIRECT_ADMIN_UTILISATEURS;
  }

  /// Supprime un compte utilisateur.
  ///
  /// @param userId    l'identifiant de l'utilisateur à supprimer
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /admin
  @PostMapping("/admin/utilisateurs/{userId}/supprimer")
  public String supprimerUtilisateur(
      @PathVariable String userId,
      RedirectAttributes redirects,
      org.springframework.security.core.Authentication auth) {
    ResponseEntity<Map<String, Object>> rep = adminController.supprimerUtilisateur(userId, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "supprimerUtilisateur");
    }
    return REDIRECT_ADMIN_UTILISATEURS;
  }

  /// Envoie un email de réinitialisation de mot de passe.
  ///
  /// @param userId    l'identifiant de l'utilisateur
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /admin
  @PostMapping("/admin/utilisateurs/{userId}/reset-mdp")
  public String reinitialiserMdp(
      @PathVariable String userId,
      RedirectAttributes redirects,
      org.springframework.security.core.Authentication auth,
      HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> rep =
        adminController.reinitialiserMdp(userId, auth, request);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "reinitialiserMdp");
    }
    return REDIRECT_ADMIN_UTILISATEURS;
  }

  /// Sauvegarde une valeur de configuration applicative.
  ///
  /// @param cle       la clé de configuration
  /// @param valeur    la valeur à sauvegarder
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /admin
  @PostMapping("/admin/config")
  public String sauvegarderConfig(
      @RequestParam String cle,
      @RequestParam(required = false, defaultValue = "") String valeur,
      RedirectAttributes redirects,
      org.springframework.security.core.Authentication auth) {
    var requete = new AdminController.RequeteConfig(cle, valeur);
    ResponseEntity<Map<String, Object>> rep = adminController.sauvegarderConfig(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "config");
    }
    return "redirect:/admin#configuration";
  }

  /// Génère de nouvelles clés VAPID.
  ///
  /// @param redirects attributs de redirection
  /// @return redirect vers /admin
  @PostMapping("/admin/vapid")
  public String genererVapid(RedirectAttributes redirects) {
    ResponseEntity<Map<String, Object>> rep = adminController.genererVapid();
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "vapid");
    }
    return "redirect:/admin#configuration";
  }

  /// Déclenche immédiatement les fetches via le worker.
  ///
  /// @param redirects attributs de redirection
  /// @return redirect vers /admin
  @PostMapping("/admin/relancer")
  public String relancerFetches(RedirectAttributes redirects) {
    ResponseEntity<Map<String, Object>> rep = adminController.relancerFetches();
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(
          CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "relancer");
    }
    return "redirect:/admin#jobs";
  }
}
