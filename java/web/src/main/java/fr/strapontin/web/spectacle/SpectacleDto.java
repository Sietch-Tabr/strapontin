/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import fr.strapontin.web.utils.Dates;
import java.time.Instant;

/**
 * Concert dans la vue spectacles d'un utilisateur — {@code
 * informationNonConfirmee} signale une absence détectée sur plusieurs
 * cycles de fetch ({@code WorkerFetchService.mettreAJourAbsences}), jamais
 * utilisé pour masquer le concert (affichage seul).
 * @since 2.1.0
 * @author Kwaite
 */
public record SpectacleDto(
    String id,
    String artiste,
    String date,
    String heure,
    String salle,
    String ville,
    String url,
    String classification,
    Instant firstSeen,
    String statut,
    boolean estNew,
    Instant presaleDebut,
    String presaleNom,
    boolean informationNonConfirmee) {

  /**
   * @return date formatée (ex. "sam. 12 avr.")
   */
  public String dateFormatee() {
    return Dates.formater(date, false);
  }

  /**
   * @return date et heure formatées
   */
  public String dateHeureFormatee() {
    return Dates.formaterAvecHeure(date, heure);
  }

  /**
   * @return {@code true} si la prévente est encore à venir
   */
  public boolean presaleActive() {
    return presaleDebut != null && presaleDebut.isAfter(Instant.now());
  }
}
