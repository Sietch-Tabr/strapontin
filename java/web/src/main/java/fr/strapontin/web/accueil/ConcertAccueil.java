/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.utils.Dates;

/// Concert dans la vue accueil d'un utilisateur.
///
/// @since 2.1.0
/// @author Kwaite
public record ConcertAccueil(
    String id,
    String artiste,
    String artisteNorm,
    String date,
    String heure,
    String salle,
    String ville,
    String url,
    String statut,
    boolean estNew) {

  /// @return date courte formatée (ex. "sam. 12 avr.")
  public String dateFormatee() {
    return Dates.formater(date, false);
  }

  /// @return date longue formatée (ex. "samedi 12 avril 2025")
  public String dateLongue() {
    return Dates.formater(date, true);
  }

  /// @return date et heure formatées
  public String dateHeureFormatee() {
    return Dates.formaterAvecHeure(date, heure);
  }
}
