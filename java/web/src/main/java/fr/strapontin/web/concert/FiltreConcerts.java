/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import java.util.List;
import org.springframework.web.bind.annotation.BindParam;

/**
 * Critères de filtrage communs aux listings de concerts.
 *
 * <p>Utilisé par {@code SpectacleService} (concerts suivis) et {@code DecouverteService}
 * (découverte par département). Les champs non pertinents pour un service sont ignorés.
 *
 * <p>Lié directement en {@code @ModelAttribute} sur les paramètres de requête des deux contrôleurs
 * (liaison par constructeur Spring) — {@code @BindParam} fait le lien avec les noms de paramètres
 * HTTP au singulier ({@code source}/{@code dept}) tandis que le constructeur compact restitue les
 * valeurs par défaut (chaîne vide, {@code "date"}) qu'un paramètre absent ne porte pas nativement.
 *
 * @param recherche terme de recherche artiste/salle (optionnel, vide = pas de filtre)
 * @param mois filtre mois YYYY-MM (optionnel)
 * @param genre filtre genre (optionnel)
 * @param classification filtre classification (optionnel)
 * @param tri {@code "date"} ou {@code "nouveaute"}
 * @param page numéro de page (0-indexé)
 * @param quinzaine {@code true} pour limiter aux 15 prochains jours (spectacles uniquement)
 * @param masques {@code true} pour afficher les concerts masqués (spectacles uniquement)
 * @param sources sources actives ({@code "artistes"}, {@code "salles"}) (spectacles uniquement)
 * @param indisponibles {@code "1"} pour inclure les concerts non disponibles (spectacles
 *     uniquement)
 * @param depts liste de départements (découverte uniquement ; null = depts utilisateur)
 * @since 2.5.4
 * @author Kwaite
 */
public record FiltreConcerts(
    @BindParam("q") String recherche,
    String mois,
    String genre,
    String classification,
    String tri,
    int page,
    boolean quinzaine,
    boolean masques,
    @BindParam("source") List<String> sources,
    String indisponibles,
    @BindParam("dept") List<String> depts) {

  // Restitue les valeurs par défaut qu'un paramètre de requête absent ne porte pas.
  public FiltreConcerts {
    recherche = recherche != null ? recherche : "";
    mois = mois != null ? mois : "";
    genre = genre != null ? genre : "";
    classification = classification != null ? classification : "";
    tri = tri != null ? tri : "date";
  }

  /**
   * Crée un filtre minimal avec les valeurs par défaut.
   *
   * @return filtre sans aucun critère actif
   */
  public static FiltreConcerts vide() {
    return new FiltreConcerts("", "", "", "", "date", 0, false, false, null, null, null);
  }
}
