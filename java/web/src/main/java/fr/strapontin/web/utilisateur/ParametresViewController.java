/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour la page des paramètres.
///
/// Délègue la logique à [UserConfigController] et [UserController].
/// Le changement de thème positionne également le cookie `theme`.
/// Le changement de mot de passe invalide la session puis redirige vers `/login`.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class ParametresViewController {

  private static final String CLE_ERREUR = "erreur";
  private static final String ERREUR_DEFAUT = "Erreur.";
  private static final String CLE_SUCCES = "succes";
  private static final String REDIRECT_PREFS = "redirect:/parametres#preferences";
  private static final String REDIRECT_COMPTE = "redirect:/parametres#compte";

  private final UserConfigController userConfigController;
  private final UserController userController;
  private final PreferencesController preferencesController;

  public ParametresViewController(
      UserConfigController userConfigController,
      UserController userController,
      PreferencesController preferencesController) {
    this.userConfigController = userConfigController;
    this.userController = userController;
    this.preferencesController = preferencesController;
  }

  /// Affiche la page des paramètres.
  ///
  /// @param modele le modèle Thymeleaf
  /// @param auth   contexte d'authentification
  /// @return le nom du template
  @GetMapping("/parametres")
  public String parametres(Model modele, Authentication auth) {
    ResponseEntity<UserConfigController.ConfigUtilisateur> repConfig =
        userConfigController.lireConfig(auth);
    ResponseEntity<UserController.ProfilUtilisateur> repProfil = userController.lireProfil(auth);

    modele.addAttribute("config", repConfig.getBody());
    modele.addAttribute("profil", repProfil.getBody());
    modele.addAttribute("currentPage", "parametres");
    return "parametres";
  }

  /// Met à jour les départements de l'utilisateur.
  ///
  /// @param departements les départements séparés par virgules
  /// @param redirects    attributs de redirection
  /// @param auth         contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/departements")
  public String majDepartements(
      @RequestParam(required = false, defaultValue = "") String departements,
      RedirectAttributes redirects,
      Authentication auth) {
    var requete = new UserConfigController.RequeteDepartements(departements);
    ResponseEntity<Map<String, Object>> rep = userConfigController.majDepartements(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "departements");
    }
    return REDIRECT_PREFS;
  }

  /// Met à jour les préférences de notification.
  ///
  /// @param frequenceNotifications la fréquence de notification
  /// @param notifArtistes          notifier pour les artistes
  /// @param notifSalles            notifier pour les salles
  /// @param redirects              attributs de redirection
  /// @param auth                   contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/notifications")
  public String majNotifications(
      @RequestParam(required = false, defaultValue = "jamais") String frequenceNotifications,
      @RequestParam(required = false, defaultValue = "false") boolean notifArtistes,
      @RequestParam(required = false, defaultValue = "false") boolean notifSalles,
      RedirectAttributes redirects,
      Authentication auth) {
    var requete =
        new UserConfigController.RequeteNotifications(
            frequenceNotifications, notifArtistes, notifSalles);
    ResponseEntity<Map<String, Object>> rep = userConfigController.majNotifications(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "notifications");
    }
    return "redirect:/parametres#notifications";
  }

  /// Met à jour le filtre de disponibilité.
  ///
  /// @param filtreDisponibles activer le filtre
  /// @param redirects         attributs de redirection
  /// @param auth              contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/filtres")
  public String majFiltres(
      @RequestParam(required = false, defaultValue = "false") boolean filtreDisponibles,
      RedirectAttributes redirects,
      Authentication auth) {
    var requete = new UserConfigController.RequeteFiltres(filtreDisponibles);
    userConfigController.majFiltres(requete, auth);
    redirects.addFlashAttribute(CLE_SUCCES, "filtres");
    return REDIRECT_PREFS;
  }

  /// Met à jour le thème et positionne le cookie {@code theme}.
  ///
  /// @param valeur    la valeur du thème (`auto`, `clair` ou `sombre`)
  /// @param redirects attributs de redirection
  /// @param response  la réponse HTTP (pour poser le cookie)
  /// @param auth      contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/theme")
  public String majTheme(
      @RequestParam String valeur,
      RedirectAttributes redirects,
      HttpServletResponse response,
      Authentication auth) {
    var requete = new UserConfigController.RequeteTheme(valeur);
    ResponseEntity<Map<String, Object>> rep = userConfigController.majTheme(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      Cookie cookie = new Cookie("theme", valeur);
      cookie.setMaxAge(365 * 24 * 3600);
      cookie.setPath("/");
      cookie.setHttpOnly(false);
      cookie.setSecure(true);
      cookie.setAttribute("SameSite", "Strict");
      response.addCookie(cookie);
      redirects.addFlashAttribute(CLE_SUCCES, "theme");
    }
    return REDIRECT_PREFS;
  }

  /// Met à jour le nom et/ou l'email.
  ///
  /// @param nom       le nouveau nom
  /// @param email     le nouvel email
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/compte")
  public String majCompte(
      @RequestParam(required = false) String nom,
      @RequestParam(required = false) String email,
      RedirectAttributes redirects,
      Authentication auth) {
    var requete = new UserController.RequeteProfil(nom, email);
    ResponseEntity<Map<String, Object>> rep = userController.majProfil(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "compte");
    }
    return REDIRECT_COMPTE;
  }

  /// Met à jour l'identifiant.
  ///
  /// @param identifiant le nouvel identifiant
  /// @param redirects   attributs de redirection
  /// @param auth        contexte d'authentification
  /// @return redirect vers /parametres
  @PostMapping("/parametres/identifiant")
  public String majIdentifiant(
      @RequestParam String identifiant, RedirectAttributes redirects, Authentication auth) {
    var requete = new UserController.RequeteIdentifiant(identifiant);
    ResponseEntity<Map<String, Object>> rep = userController.majIdentifiant(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "identifiant");
    }
    return REDIRECT_COMPTE;
  }

  /// Change le mot de passe, invalide la session et redirige vers /login.
  ///
  /// @param motDePasseActuel  le mot de passe actuel
  /// @param motDePasseNouveau le nouveau mot de passe
  /// @param redirects         attributs de redirection
  /// @param auth              contexte d'authentification
  /// @param request           la requête HTTP (pour invalider la session)
  /// @return redirect vers /login si succès, sinon redirect vers /parametres
  @PostMapping("/parametres/motdepasse")
  public String changerMotDePasse(
      @RequestParam String motDePasseActuel,
      @RequestParam String motDePasseNouveau,
      RedirectAttributes redirects,
      Authentication auth,
      HttpServletRequest request) {
    var requete = new UserController.RequeteMotDePasse(motDePasseActuel, motDePasseNouveau);
    ResponseEntity<Map<String, Object>> rep = userController.changerMotDePasse(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
      return REDIRECT_COMPTE;
    }
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return "redirect:/login?reset=true";
  }

  /// Importe les préférences depuis un fichier JSON.
  ///
  /// @param fichier        le fichier JSON exporté par Strapontin
  /// @param mode           `fusionner` ou `ecraser`
  /// @param importerConfig importer également la configuration
  /// @param redirects      attributs de redirection
  /// @param auth           contexte d'authentification
  /// @return redirect vers /parametres#donnees
  @PostMapping(value = "/parametres/importer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public String importer(
      @RequestParam MultipartFile fichier,
      @RequestParam(defaultValue = "fusionner") String mode,
      @RequestParam(defaultValue = "false") boolean importerConfig,
      RedirectAttributes redirects,
      Authentication auth) {
    ResponseEntity<Map<String, Object>> rep =
        preferencesController.importer(fichier, mode, importerConfig, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute(CLE_ERREUR, corps != null ? corps.get(CLE_ERREUR) : ERREUR_DEFAUT);
    } else {
      redirects.addFlashAttribute(CLE_SUCCES, "import");
    }
    return "redirect:/parametres#donnees";
  }

  /// Supprime le compte et redirige vers /login.
  ///
  /// @param auth    contexte d'authentification
  /// @param request la requête HTTP (pour invalider la session)
  /// @return redirect vers /login
  @PostMapping("/parametres/supprimer-compte")
  public String supprimerCompte(Authentication auth, HttpServletRequest request) {
    userController.supprimerCompte(auth, request);
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return "redirect:/login";
  }
}
