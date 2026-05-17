/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour les pages statiques (aide, changelog).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AideViewController {

  private final EmailService emailService;

  public AideViewController(EmailService emailService) {
    this.emailService = emailService;
  }

  /// Affiche la page d'aide.
  ///
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template
  @GetMapping("/aide")
  public String aide(Model modele) {
    modele.addAttribute("currentPage", "aide");
    return "aide";
  }

  /// Affiche le journal des modifications.
  ///
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template
  @GetMapping("/changelog")
  public String changelog(Model modele) {
    modele.addAttribute("currentPage", "changelog");
    return "changelog";
  }

  /// Envoie un signalement à l'adresse de contact.
  ///
  /// @param type      le type (`bug`, `suggestion`, `question`)
  /// @param message   le message de l'utilisateur
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /aide
  @PostMapping("/aide/signalement")
  public String signaler(
      @RequestParam String type,
      @RequestParam String message,
      RedirectAttributes redirects,
      Authentication auth) {
    UtilisateurDetails utilisateur = (UtilisateurDetails) auth.getPrincipal();
    String msg = message == null ? "" : message.strip();
    if (msg.isEmpty()) {
      redirects.addFlashAttribute("erreurSignalement", "Le message est requis.");
      return "redirect:/aide";
    }
    String typeValide = Set.of("bug", "suggestion", "question").contains(type) ? type : "question";
    emailService.envoyerSignalement(typeValide, msg, utilisateur.email(), utilisateur.nom());
    redirects.addFlashAttribute("succesSignalement", true);
    return "redirect:/aide";
  }
}
