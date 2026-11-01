/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import fr.strapontin.core.DistanceGeo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Détails d'un candidat Wikidata rejeté sur divergence de position : sans
 * le candidat ni l'écart, le signalement n'est pas exploitable.
 * @since 3.0.6
 * @author Kwaite
 */
final class DetailsDivergenceWikidata {

  // Au-delà, l'élément est un homonyme évident : aucune revue humaine à demander.
  private static final long ECART_MAX_A_SIGNALER_METRES = 20_000;

  private DetailsDivergenceWikidata() {}

  /**
   * @param salle salle recherchée
   * @param itemUri adresse de l'élément Wikidata rejeté
   * @param itemLabel libellé de l'élément, peut être vide
   * @param coordLat latitude de l'élément, peut être {@code null}
   * @param coordLon longitude de l'élément, peut être {@code null}
   * @return les détails à signaler, vide si l'écart est trop grand pour une revue
   */
  /* package-private : appelé par WikidataSalleDecouverte (même package). */
  static Optional<Map<String, Object>> detailsASignaler(
      final SalleAEnrichir salle,
      final String itemUri,
      final String itemLabel,
      final Double coordLat,
      final Double coordLon) {
    final Map<String, Object> details = detailsDe(salle, itemUri, itemLabel, coordLat, coordLon);
    final boolean tropEloigne =
        details.get("ecartMetres") instanceof Number ecart
            && ecart.longValue() > ECART_MAX_A_SIGNALER_METRES;
    return tropEloigne ? Optional.empty() : Optional.of(details);
  }

  /**
   * @param salle salle recherchée
   * @param itemUri adresse de l'élément Wikidata rejeté
   * @param itemLabel libellé de l'élément, peut être vide
   * @param coordLat latitude de l'élément, peut être {@code null}
   * @param coordLon longitude de l'élément, peut être {@code null}
   * @return identifiant, libellé et écart en mètres quand les positions sont connues
   */
  /* package-private : appelé par WikidataSalleDecouverte (même package). */
  static Map<String, Object> detailsDe(
      final SalleAEnrichir salle,
      final String itemUri,
      final String itemLabel,
      final Double coordLat,
      final Double coordLon) {
    final Map<String, Object> details = new LinkedHashMap<>();
    details.put("wikidataId", itemUri.substring(itemUri.lastIndexOf('/') + 1));
    if (itemLabel != null && !itemLabel.isBlank()) {
      details.put("libelleWikidata", itemLabel);
    }
    if (salle.latitude() != null
        && salle.longitude() != null
        && coordLat != null
        && coordLon != null) {
      details.put(
          "ecartMetres",
          Math.round(DistanceGeo.metres(salle.latitude(), salle.longitude(), coordLat, coordLon)));
    }
    return details;
  }
}
