/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.decouverte;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour la page de découverte.
///
/// <p>Délègue le chargement des données à {@link DecouverteController}.
/// Le déclenchement du fetch passe par un formulaire POST (PRG pattern).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class DecouverteViewController {

  private final DecouverteController decouverteController;

  public DecouverteViewController(DecouverteController decouverteController) {
    this.decouverteController = decouverteController;
  }

  /// Affiche la page de découverte avec les concerts filtrés par département.
  ///
  /// @param depts          les départements sélectionnés
  /// @param q              terme de recherche
  /// @param mois           filtre mois (YYYY-MM)
  /// @param genre          filtre genre
  /// @param classification filtre classification
  /// @param budget         budget maximum
  /// @param tri            critère de tri ({@code date} ou {@code nouveaute})
  /// @param page           numéro de page (0-indexé)
  /// @param modele         le modèle Thymeleaf
  /// @param auth           contexte d'authentification
  /// @return le nom du template
  @GetMapping("/decouverte")
  public String decouverte(
      @RequestParam(name = "dept", required = false) List<String> depts,
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "") String budget,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "0") int page,
      Model modele,
      Authentication auth) {

    ResponseEntity<DecouverteController.ReponseDecouverte> rep =
        decouverteController.decouvrir(
            depts, q, mois, genre, classification, budget, tri, page, auth);

    DecouverteController.ReponseDecouverte donnees = rep.getBody();
    modele.addAttribute("donnees", donnees);
    modele.addAttribute("depts", depts != null ? depts : List.of());
    modele.addAttribute("q", q);
    modele.addAttribute("mois", mois);
    modele.addAttribute("genre", genre);
    modele.addAttribute("classification", classification);
    modele.addAttribute("budget", budget);
    modele.addAttribute("tri", tri);
    modele.addAttribute("page", page);
    modele.addAttribute("currentPage", "decouverte");

    Map<String, String> syncDepts = new LinkedHashMap<>();
    if (donnees != null) {
      for (DecouverteController.SyncDept s : donnees.syncParDept()) {
        if (s.derniereExec() != null) {
          syncDepts.put(
              s.dept(), s.derniereExec().toString().substring(0, 16).replace('T', ' '));
        }
      }
    }
    modele.addAttribute("syncDepts", syncDepts);
    return "decouverte";
  }

  /// Déclenche un fetch régional et redirige vers la découverte.
  ///
  /// @param redirects attributs de redirection
  /// @return redirect vers /decouverte
  @PostMapping("/decouverte/fetcher")
  public String fetcher(RedirectAttributes redirects) {
    var requete = new DecouverteController.RequeteFetch(List.of());
    var rep = decouverteController.fetcher(requete);
    if (rep.getStatusCode().isError()) {
      var corps = (java.util.Map<?, ?>) rep.getBody();
      redirects.addFlashAttribute("erreur", corps != null ? corps.get("erreur") : "Erreur.");
    } else {
      redirects.addFlashAttribute("succes", "fetch");
    }
    return "redirect:/decouverte";
  }
}
