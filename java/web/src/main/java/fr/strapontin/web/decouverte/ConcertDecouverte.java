/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import fr.strapontin.web.utils.Dates;
import java.time.Instant;

/// Concert dans la vue découverte par département.
///
/// @since 2.1.0
/// @author Kwaite
public record ConcertDecouverte(
    String id,
    String artiste,
    String date,
    String heure,
    String salle,
    String ville,
    String dept,
    String url,
    String classification,
    String genre,
    String statut,
    Instant firstSeen) {

  /// @return date courte formatée
  public String dateFormatee() {
    return Dates.formater(date, false);
  }

  /// @return date et heure formatées
  public String dateHeureFormatee() {
    return Dates.formaterAvecHeure(date, heure);
  }
}
