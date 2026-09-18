/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.Normalisation;
import java.util.Map;

/**
 * Extraction des informations de salle (venue) d'un événement Ticketmaster brut
 * — extrait de {@link MapeurEvenementTM} pour rester sous le seuil de
 * complexité cyclomatique de classe (50).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class ExtracteurVenueTM {

  private static final String CLE_NAME = "name";
  private static final String UNCHECKED = "unchecked";

  private ExtracteurVenueTM() {}

  /* package-private : appelé par MapeurEvenementTM (même package). */
  record InfosVenueTM(String salle, String ville, String dept, String salleSourceId) {}

  /* package-private : appelé par MapeurEvenementTM (même package). */
  static InfosVenueTM extraireInfosVenueTM(final Map<String, Object> venue) {
    return new InfosVenueTM(
        extraireNomSalleTM(venue),
        extraireVilleTM(venue),
        extraireDeptTM(venue),
        extraireSalleSourceIdTM(venue));
  }

  private static String extraireNomSalleTM(final Map<String, Object> venue) {
    return Normalisation.normaliserCasse(
        venue != null && venue.get(CLE_NAME) != null
            ? (String) venue.get(CLE_NAME)
            : "Salle inconnue");
  }

  @SuppressWarnings(UNCHECKED)
  private static String extraireVilleTM(final Map<String, Object> venue) {
    final Map<String, String> cityMap =
        venue != null ? (Map<String, String>) venue.get("city") : null;
    return cityMap != null ? cityMap.get(CLE_NAME) : null;
  }

  @SuppressWarnings(UNCHECKED)
  private static String extraireDeptTM(final Map<String, Object> venue) {
    // Un code postal étranger dont les deux premiers chiffres collisionnent
    // avec un département français existant (ex. Luxembourg 43xx / Haute-Loire
    // 43) produirait sinon un dept français plausible mais faux — countryCode
    // absent traité comme française par défaut (comportement historique, aucun
    // cas contraire connu).
    final Map<String, String> countryMap =
        venue != null ? (Map<String, String>) venue.get("country") : null;
    final String countryCode = countryMap != null ? countryMap.get("countryCode") : null;
    return countryCode == null || "FR".equals(countryCode)
        ? Normalisation.extraireDeptFrancais(
            venue != null ? (String) venue.get("postalCode") : null)
        : null;
  }

  private static String extraireSalleSourceIdTM(final Map<String, Object> venue) {
    final Object venueIdObj = venue != null ? venue.get("id") : null;
    return venueIdObj != null ? "ticketmaster:" + venueIdObj : null;
  }
}
