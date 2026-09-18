/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Set;

/**
 * Fusion/déduplication des suggestions de salle (base, Nominatim,
 * Ticketmaster) — extrait de {@link SalleService} (limite 500 lignes).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class SalleSuggestions {

  private SalleSuggestions() {}

  /**
   * Fusionne un candidat externe avec une suggestion désignant la même salle
   * ({@link CorrespondanceSalle#estDoublonSalle}, même algorithme que {@link
   * SalleService#resoudreSalleId}), ou l'ajoute sinon.
   * @param liste suggestions déjà collectées (modifiée en place)
   * @param candidat nouveau résultat externe à intégrer
   */
  /* package-private : appelé par SalleService (même package). */
  static void fusionnerOuAjouter(final List<ResultatSalle> liste, final ResultatSalle candidat) {
    for (int i = 0; i < liste.size(); i++) {
      final ResultatSalle existant = liste.get(i);
      if (CorrespondanceSalle.estDoublonSalle(
          Normalisation.normaliserTexte(existant.salle()),
          Normalisation.normaliserTexte(candidat.salle()))) {
        liste.set(i, fusionner(existant, candidat));
        return;
      }
    }
    liste.add(candidat);
  }

  private static ResultatSalle fusionner(
      final ResultatSalle existant, final ResultatSalle candidat) {
    return new ResultatSalle(
        existant.salle(),
        preferer(existant.ville(), candidat.ville()),
        preferer(existant.tmVenueId(), candidat.tmVenueId()),
        preferer(existant.digitickSlug(), candidat.digitickSlug()),
        existant.source(),
        preferer(existant.osmId(), candidat.osmId()),
        preferer(existant.wikidataId(), candidat.wikidataId()));
  }

  private static String preferer(final String existant, final String candidat) {
    return existant != null ? existant : candidat;
  }

  /**
   * Indique si une recherche correspond, même partiellement, à une salle
   * déjà suivie (évite un faux "Aucun résultat" pour un terme partiel, ex.
   * "Zenith" absent de "zenithparis").
   * @param rechercheNorm clé normalisée de la recherche
   * @param clesNorm clés normalisées des salles déjà suivies
   * @return {@code true} si une salle déjà suivie contient la recherche
   */
  /* package-private : appelé par SalleService (même package). */
  static boolean contientCorrespondancePartielle(
      final String rechercheNorm, final Set<String> clesNorm) {
    return clesNorm.stream().anyMatch(cle -> cle.contains(rechercheNorm));
  }
}
