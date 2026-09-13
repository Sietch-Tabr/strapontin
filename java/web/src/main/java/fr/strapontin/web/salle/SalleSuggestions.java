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
 * Fusion/déduplication des suggestions de salle collectées depuis plusieurs sources (base,
 * Nominatim, Ticketmaster).
 *
 * <p>Extrait de {@link SalleService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class SalleSuggestions {

  private SalleSuggestions() {}

  /**
   * Fusionne un candidat externe avec une suggestion déjà présente désignant la même salle ({@link
   * CorrespondanceSalle#estDoublonSalle}), ou l'ajoute comme nouvelle suggestion sinon.
   *
   * <p>Utilise le même algorithme que {@link SalleService#resoudreSalleId} — avant correctif,
   * {@code fusionnerOuAjouter} utilisait sa propre implémentation Levenshtein locale ({@code
   * estMemeSalle}), ce qui pouvait afficher deux suggestions distinctes dans l'UI alors qu'un clic
   * sur l'une ou l'autre aurait résolu vers le même UUID via {@code resoudreSalleId} — un seul
   * algorithme de dédoublonnage salle dans tout le projet.
   *
   * <p>Permet à Nominatim et Ticketmaster de contribuer chacun leurs identifiants (osmId,
   * tmVenueId) à une même suggestion plutôt que de créer deux entrées dupliquées.
   *
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
   * Indique si une recherche correspond, même partiellement, à une salle déjà suivie.
   *
   * <p>Une égalité stricte laissait passer un faux "Aucun résultat" quand la recherche est un terme
   * partiel (ex. "Zenith" ne matchait pas "zenithparis") alors que la salle a bien été trouvée puis
   * exclue des suggestions car déjà suivie — l'utilisateur n'avait alors aucun signal expliquant
   * l'absence de résultat.
   *
   * @param rechercheNorm clé normalisée de la recherche ({@link SalleService#normaliserSalleCle})
   * @param clesNorm clés normalisées des salles déjà suivies
   * @return {@code true} si une salle déjà suivie contient la recherche
   */
  /* package-private : appelé par SalleService (même package). */
  static boolean contientCorrespondancePartielle(
      final String rechercheNorm, final Set<String> clesNorm) {
    return clesNorm.stream().anyMatch(cle -> cle.contains(rechercheNorm));
  }
}
