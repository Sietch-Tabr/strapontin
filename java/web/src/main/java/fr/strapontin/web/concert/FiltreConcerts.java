/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import java.util.List;

/// Critères de filtrage communs aux listings de concerts.
///
/// <p>Utilisé par {@code SpectacleService} (concerts suivis) et {@code DecouverteService}
/// (découverte par département). Les champs non pertinents pour un service sont ignorés.
///
/// @param q              terme de recherche artiste/salle (optionnel, vide = pas de filtre)
/// @param mois           filtre mois YYYY-MM (optionnel)
/// @param genre          filtre genre (optionnel)
/// @param classification filtre classification (optionnel)
/// @param tri            {@code "date"} ou {@code "nouveaute"}
/// @param page           numéro de page (0-indexé)
/// @param quinzaine      {@code true} pour limiter aux 15 prochains jours (spectacles uniquement)
/// @param masques        {@code true} pour afficher les concerts masqués (spectacles uniquement)
/// @param sources        sources actives ({@code "artistes"}, {@code "salles"})
///                       (spectacles uniquement)
/// @param indisponibles  {@code "1"} pour inclure les concerts non disponibles
///                       (spectacles uniquement)
/// @param depts          liste de départements (découverte uniquement ; null = depts utilisateur)
/// @since 2.5.4
/// @author Kwaite
public record FiltreConcerts(
    String q,
    String mois,
    String genre,
    String classification,
    String tri,
    int page,
    boolean quinzaine,
    boolean masques,
    List<String> sources,
    String indisponibles,
    List<String> depts) {

  /// Crée un filtre minimal avec les valeurs par défaut.
  ///
  /// @return filtre sans aucun critère actif
  public static FiltreConcerts vide() {
    return new FiltreConcerts("", "", "", "", "date", 0, false, false, null, null, null);
  }
}
