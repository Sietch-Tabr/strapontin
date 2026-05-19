/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour le flow de première connexion.
///
/// <p>Accessible uniquement si {@code doitChangerCredentials} est vrai.
/// Délègue la validation et la mise à jour à {@link UserController}.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class FirstLoginViewController {

  private final UserController userController;

  public FirstLoginViewController(UserController userController) {
    this.userController = userController;
  }

  /// Affiche le formulaire de première connexion.
  ///
  /// @param auth   contexte d'authentification
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template ou redirect si non requis
  @GetMapping("/first-login")
  public String formulaire(Authentication auth, Model modele) {
    UtilisateurDetails u = (UtilisateurDetails) auth.getPrincipal();
    if (!u.doitChangerCredentials()) {
      return "redirect:/";
    }
    modele.addAttribute("currentPage", "first-login");
    return "auth/first-login";
  }

  /// Traite le formulaire de première connexion.
  ///
  /// @param nom               le nom d'affichage
  /// @param identifiant       le nouvel identifiant
  /// @param motDePasseActuel  le mot de passe temporaire
  /// @param motDePasseNouveau le nouveau mot de passe
  /// @param auth              contexte d'authentification
  /// @param request           la requête HTTP (pour invalider la session)
  /// @param response          la réponse HTTP
  /// @param redirects         attributs de redirection
  /// @return redirect vers /login si succès, sinon réaffiche le formulaire
  @PostMapping("/first-login")
  public String traiter(
      @RequestParam String nom,
      @RequestParam String identifiant,
      @RequestParam String motDePasseActuel,
      @RequestParam String motDePasseNouveau,
      Authentication auth,
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes redirects) {

    var requete =
        new UserController.RequetePremierLogin(
            nom, identifiant, motDePasseActuel, motDePasseNouveau);
    ResponseEntity<?> rep = userController.premierLogin(requete, auth, request);

    if (rep.getStatusCode().isError()) {
      var corps = (java.util.Map<?, ?>) rep.getBody();
      redirects.addFlashAttribute("erreur", corps != null ? corps.get("erreur") : "Erreur.");
      return "redirect:/first-login";
    }

    // Session invalidée par UserController — déconnecter Spring Security
    SecurityContextHolder.clearContext();
    return "redirect:/login?reset=true";
  }
}
