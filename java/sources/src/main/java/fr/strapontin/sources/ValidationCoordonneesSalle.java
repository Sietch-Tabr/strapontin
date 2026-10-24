/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.Normalisation;
import java.util.Set;

/**
 * Validation de cohérence GPS d'un candidat de découverte Wikidata contre
 * la salle recherchée — extrait de {@link WikidataSalleDecouverte} (dette
 * de taille pure, aucun changement fonctionnel).
 * @since 3.0.0
 * @author Kwaite
 */
final class ValidationCoordonneesSalle {

  // Distance au-delà de laquelle un candidat Wikidata ne peut structurellement
  // pas être la salle déjà connue, même en cas de nom/ville/dept compatibles
  // — même seuil que FusionSallesClePorteeService (worker.persister), un
  // arrondissement différent peut partager dept et nom approximatif
  // (Alhambra Paris, ancien théâtre 11e vs salle de concert active 10e).
  private static final double DISTANCE_MAX_METRES = 500;

  // Même commune sans arrondissements : la position approximative d'une
  // billetterie peut s'écarter de l'élément sans que ce soit un autre lieu.
  private static final double DISTANCE_MAX_MEME_COMMUNE_METRES = 1000;
  private static final Set<String> COMMUNES_A_ARRONDISSEMENTS =
      Set.of("paris", "lyon", "marseille");

  private ValidationCoordonneesSalle() {}

  /**
   * Jamais de rejet sur une coordonnée inconnue. Sans position de la salle,
   * le candidat doit rester dans son département.
   * @return {@code true} si les positions sont distantes de plus de
   *     {@value #DISTANCE_MAX_METRES} m, ou le candidat hors du département
   */
  /* package-private : appelé uniquement par WikidataSalleDecouverte (même package). */
  static boolean incompatibles(
      final SalleAEnrichir salle,
      final Double coordLat,
      final Double coordLon,
      final String communeCandidat) {
    if (coordLat == null || coordLon == null) {
      return false;
    }
    if (salle.latitude() == null || salle.longitude() == null) {
      return !PositionDepartement.plausible(salle.dept(), coordLat, coordLon);
    }
    return !DistanceGeo.connuesEtProches(
        salle.latitude(),
        salle.longitude(),
        coordLat,
        coordLon,
        distanceMax(salle.ville(), communeCandidat));
  }

  private static double distanceMax(final String villeSalle, final String communeCandidat) {
    if (villeSalle == null || communeCandidat == null) {
      return DISTANCE_MAX_METRES;
    }
    final String ville = Normalisation.normaliserTexte(villeSalle);
    final boolean memeCommune = ville.equals(Normalisation.normaliserTexte(communeCandidat));
    return memeCommune && !COMMUNES_A_ARRONDISSEMENTS.contains(ville)
        ? DISTANCE_MAX_MEME_COMMUNE_METRES
        : DISTANCE_MAX_METRES;
  }
}
