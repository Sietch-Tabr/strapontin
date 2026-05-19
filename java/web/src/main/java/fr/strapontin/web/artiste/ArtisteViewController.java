/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.artiste;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour la page des artistes suivis.
///
/// Délègue la logique à [ArtisteController] et applique le PRG pattern.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class ArtisteViewController {

  private final ArtisteController artisteController;

  public ArtisteViewController(ArtisteController artisteController) {
    this.artisteController = artisteController;
  }

  /// Affiche la liste des artistes suivis avec suggestions de recherche.
  ///
  /// @param q      terme de recherche (optionnel)
  /// @param modele le modèle Thymeleaf
  /// @param auth   contexte d'authentification
  /// @return le nom du template
  @GetMapping("/artistes")
  public String artistes(
      @RequestParam(required = false, defaultValue = "") String q,
      Model modele,
      Authentication auth) {
    var rep = artisteController.listerArtistes(q, auth);
    modele.addAttribute("donnees", rep.getBody());
    modele.addAttribute("q", q);
    modele.addAttribute("currentPage", "artistes");
    return "artistes";
  }

  /// Ajoute un artiste à la liste de suivi.
  ///
  /// @param nom        le nom de l'artiste à ajouter
  /// @param redirects  les attributs de redirection (erreur éventuelle)
  /// @param auth       contexte d'authentification
  /// @return redirect vers /artistes (PRG)
  @PostMapping("/artistes/ajouter")
  public String ajouter(
      @RequestParam String nom, RedirectAttributes redirects, Authentication auth) {
    var requete = new ArtisteController.RequeteAjouter(nom);
    var rep = artisteController.ajouterArtiste(requete, auth);
    if (rep.getStatusCode().isError()) {
      var corps = rep.getBody();
      redirects.addFlashAttribute("erreur", corps != null ? corps.get("erreur") : "Erreur.");
    } else {
      redirects.addFlashAttribute("fetchEnCours", nom);
    }
    return "redirect:/artistes";
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// @param artisteNormalise l'artiste normalisé à supprimer
  /// @param auth             contexte d'authentification
  /// @return redirect vers /artistes (PRG)
  @PostMapping("/artistes/supprimer/{artisteNormalise}")
  public String supprimer(@PathVariable String artisteNormalise, Authentication auth) {
    artisteController.supprimerArtiste(artisteNormalise, auth);
    return "redirect:/artistes";
  }
}
