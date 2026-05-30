/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.vue.OngletItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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

  private static final Map<String, String> DESCRIPTIONS_CONFIG =
      Map.ofEntries(
          Map.entry("TICKETMASTER_API_KEY", "Clé d'API Ticketmaster Discovery v2."),
          Map.entry("RESEND_API_KEY", "Clé d'API Resend pour les notifications email."),
          Map.entry(
              "RESEND_WEBHOOK_SECRET",
              "Clé de signature des webhooks Resend (détection des bounces email)."
                  + " À récupérer dans le dashboard Resend → Webhooks → créer un endpoint"
                  + " avec l'URL PUBLIC_BASE_URL/api/webhooks/resend"
                  + " (événements : email.bounced, email.complained)."),
          Map.entry("CONTACT_EMAIL", "Adresse email affichée dans les emails envoyés."),
          Map.entry(
              "PUBLIC_BASE_URL",
              "URL publique de l'application (ex. https://strapontin.kwaite.fr)."),
          Map.entry("VAPID_PUBLIC_KEY", "Clé publique VAPID (générée automatiquement)."),
          Map.entry("VAPID_PRIVATE_KEY", "Clé privée VAPID (générée automatiquement)."));

  private static final Map<String, String> LABELS_JOBS =
      Map.of(
          "fetch:artistes:batch", "Artistes suivis",
          "fetch:region", "Concerts par région",
          "notifier:digest", "Notifications digest",
          "purger:concerts", "Purge concerts",
          "recalculer:matches", "Recalcul matchs",
          "fetch:salle", "Salle");

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
  public String admin(
      Model modele,
      org.springframework.security.core.Authentication auth,
      HttpServletRequest request,
      HttpServletResponse response) {
    ResponseEntity<List<UtilisateurAdmin>> repUsers = adminController.listerUtilisateurs();
    ResponseEntity<List<ConfigStatut>> repConfig = adminController.lireConfigStatuts();
    ResponseEntity<List<LogAdminService.EntreeLogAdmin>> repLogs = adminController.lireLogs();
    ResponseEntity<Map<String, Object>> repJobs = adminController.lireJobs();

    modele.addAttribute("utilisateurs", repUsers.getBody());
    modele.addAttribute("configStatuts", repConfig.getBody());
    modele.addAttribute("logs", repLogs.getBody());
    var corpsJobs = repJobs.getBody();
    modele.addAttribute("statsJobs", corpsJobs != null ? corpsJobs.get("statsJobs") : null);
    modele.addAttribute("quotaTM", corpsJobs != null ? corpsJobs.get("quotaTM") : null);
    modele.addAttribute(
        "jobsEnCours",
        corpsJobs != null ? corpsJobs.getOrDefault("jobsEnCours", List.of()) : List.of());

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

    String etatQuota =
        corpsJobs != null ? (String) corpsJobs.getOrDefault("etatQuota", "ok") : "ok";
    modele.addAttribute("etatQuota", etatQuota);
    modele.addAttribute("labelsJobs", LABELS_JOBS);

    @SuppressWarnings("unchecked")
    List<StatJob> statsJobsList =
        corpsJobs != null ? (List<StatJob>) corpsJobs.get("statsJobs") : List.of();
    long nbJobsOk = statsJobsList.stream().filter(j -> j.erreur() == null).count();
    long nbJobsErreur = statsJobsList.stream().filter(j -> j.erreur() != null).count();
    modele.addAttribute("nbJobsOk", nbJobsOk);
    modele.addAttribute("nbJobsErreur", nbJobsErreur);

    List<ConfigStatut> configStatuts = repConfig.getBody();
    String baseUrl =
        configStatuts != null
            ? configStatuts.stream()
                .filter(s -> "PUBLIC_BASE_URL".equals(s.cle()) && s.valeur() != null)
                .map(ConfigStatut::valeur)
                .findFirst()
                .orElse("PUBLIC_BASE_URL")
            : "PUBLIC_BASE_URL";
    Map<String, String> descriptionsConfig = new java.util.HashMap<>(DESCRIPTIONS_CONFIG);
    descriptionsConfig.put(
        "RESEND_WEBHOOK_SECRET",
        "Clé de signature des webhooks Resend (détection des bounces email)."
            + " À récupérer dans le dashboard Resend → Webhooks → créer un endpoint"
            + " avec l'URL "
            + baseUrl
            + "/api/webhooks/resend"
            + " (événements : email.bounced, email.complained).");
    modele.addAttribute("descriptionsConfig", descriptionsConfig);
    Map<String, ConfigStatut> configParCle =
        configStatuts == null
            ? Map.of()
            : configStatuts.stream().collect(Collectors.toMap(ConfigStatut::cle, s -> s));
    modele.addAttribute("configParCle", configParCle);
    modele.addAttribute("appConfigSecretPresente", System.getenv("APP_CONFIG_SECRET") != null);
    String utilisateurCourantId =
        auth != null
                && auth.getPrincipal() instanceof fr.strapontin.web.securite.UtilisateurDetails u
            ? u.id()
            : null;
    modele.addAttribute("utilisateurCourantId", utilisateurCourantId);
    modele.addAttribute("currentPage", "admin");
    modele.addAttribute(
        "ongletItems",
        List.of(
            new OngletItem("#jobs", "Tableau de bord", "icon-activity"),
            new OngletItem("#logs", "Logs", "icon-scroll-text"),
            new OngletItem("#configuration", "Configuration", "icon-settings"),
            new OngletItem("#section-banniere", "Bannière", "icon-bell"),
            new OngletItem("#utilisateurs", "Utilisateurs", "icon-users")));
    DateTimeFormatter formateur =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy à HH'h'mm", Locale.FRENCH);
    modele.addAttribute("heureActualisation", LocalDateTime.now().format(formateur));

    if ("true".equals(request.getHeader("HX-Request"))) {
      Object succes = modele.asMap().get("succes");
      String hash =
          switch (String.valueOf(succes)) {
            case "supprimerUtilisateur", "creerUtilisateur", "reinitialiserMdp" -> "#utilisateurs";
            case "config", "vapid" -> "#configuration";
            case "relancer" -> "#jobs";
            default -> null;
          };
      if (hash != null) response.setHeader("HX-Push-Url", "/admin" + hash);
    }

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
    var requete = new RequeteCreerUtilisateur(email);
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
    var requete = new RequeteConfig(cle, valeur);
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
