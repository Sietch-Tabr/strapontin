/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour la page des salles suivies.
///
/// <p>Délègue la logique à {@link SalleController} et applique le PRG pattern.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class SalleViewController {

  private final SalleController salleController;

  public SalleViewController(SalleController salleController) {
    this.salleController = salleController;
  }

  /// Affiche la liste des salles suivies avec suggestions de recherche.
  ///
  /// @param q      terme de recherche (optionnel)
  /// @param modele le modèle Thymeleaf
  /// @param auth   contexte d'authentification
  /// @return le nom du template
  @GetMapping("/salles")
  public String salles(
      @RequestParam(required = false, defaultValue = "") String q,
      Model modele,
      Authentication auth) {
    var rep = salleController.listerSalles(q, auth);
    modele.addAttribute("donnees", rep.getBody());
    modele.addAttribute("q", q);
    modele.addAttribute("currentPage", "salles");
    return "salles";
  }

  /// Ajoute une salle à la liste de suivi.
  ///
  /// @param nom       le nom de la salle
  /// @param tmVenueId l'identifiant Ticketmaster (optionnel)
  /// @param redirects les attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /salles (PRG)
  @PostMapping("/salles/ajouter")
  public String ajouter(
      @RequestParam String nom,
      @RequestParam(required = false) String tmVenueId,
      RedirectAttributes redirects,
      Authentication auth) {
    var requete = new RequeteAjouter(nom, tmVenueId);
    var rep = salleController.ajouterSalle(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = (java.util.Map<?, ?>) rep.getBody();
      redirects.addFlashAttribute("erreur", corps != null ? corps.get("erreur") : "Erreur.");
    } else {
      redirects.addFlashAttribute("succes", true);
    }
    return "redirect:/salles";
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param salleId   l'identifiant de la salle à supprimer
  /// @param redirects les attributs de redirection
  /// @param auth      contexte d'authentification
  /// @return redirect vers /salles (PRG)
  @PostMapping("/salles/supprimer/{salleId}")
  public String supprimer(
      @PathVariable String salleId, RedirectAttributes redirects, Authentication auth) {
    salleController.supprimerSalle(salleId, auth);
    redirects.addFlashAttribute("supprime", true);
    return "redirect:/salles";
  }
}
