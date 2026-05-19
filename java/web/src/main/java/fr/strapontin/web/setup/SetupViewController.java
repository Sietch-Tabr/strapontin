/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.setup;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/// Contrôleur MVC Thymeleaf pour la page de configuration initiale ({@code /setup}).
///
/// <p>Accessible sans authentification. Redirige vers {@code /login} si l'application
/// est déjà configurée.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class SetupViewController {

  private final SetupController setupController;

  public SetupViewController(SetupController setupController) {
    this.setupController = setupController;
  }

  /// Affiche le formulaire de configuration initiale.
  ///
  /// <p>Redirige vers {@code /login} si au moins un utilisateur existe.
  ///
  /// @return le nom du template Thymeleaf ou une redirection
  @GetMapping("/setup")
  public String afficherSetup() {
    ResponseEntity<SetupController.ReponseSetup> reponse = setupController.verifierSetup();
    SetupController.ReponseSetup corps = reponse.getBody();
    if (corps != null && corps.dejaConfiguree()) {
      return "redirect:/login";
    }
    return "setup";
  }

  /// Traite le formulaire de configuration initiale.
  ///
  /// @param nom          le nom d'affichage de l'administrateur
  /// @param identifiant  l'identifiant de connexion
  /// @param email        l'adresse email
  /// @param motDePasse   le mot de passe (12 caractères minimum)
  /// @param baseUrl      l'URL publique de l'application (optionnel)
  /// @param contactEmail l'email de contact (optionnel)
  /// @param modele       le modèle Thymeleaf
  /// @return redirect vers {@code /login?configure=true} en cas de succès, sinon la vue avec erreur
  @PostMapping("/setup")
  public String traiterSetup(
      @RequestParam(required = false) String nom,
      @RequestParam(required = false) String identifiant,
      @RequestParam(required = false) String email,
      @RequestParam(required = false) String motDePasse,
      @RequestParam(required = false) String baseUrl,
      @RequestParam(required = false) String contactEmail,
      Model modele) {

    SetupController.RequeteSetup requete =
        new SetupController.RequeteSetup(
            nom, identifiant, email, motDePasse, baseUrl, contactEmail);

    ResponseEntity<Map<String, Object>> reponse = setupController.initialiser(requete);

    if (reponse.getStatusCode().is2xxSuccessful()) {
      return "redirect:/login?configure=true";
    }

    Map<String, Object> corps = reponse.getBody();
    String erreur = corps != null ? (String) corps.get("erreur") : "Erreur inattendue.";
    modele.addAttribute("erreur", erreur);
    modele.addAttribute("nom", nom);
    modele.addAttribute("identifiant", identifiant);
    modele.addAttribute("email", email);
    modele.addAttribute("baseUrl", baseUrl);
    modele.addAttribute("contactEmail", contactEmail);
    return "setup";
  }
}
