/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Concert brut tel que renvoyé par une source (TM, Eventim,
 * Digitick), avant dédoublonnage/résolution du référentiel —
 * champs auto-descriptifs, tous nullable sauf artiste/date/
 * salle/source (validés au constructeur compact).
 */
public record ConcertBrut(
    String artiste,
    String date,
    String heure,
    String salle,
    String ville,
    String dept,
    String url,
    String source,
    String classification,
    String genre,
    String statut,
    String presaleDebut,
    String presaleNom,
    String salleSourceId,
    String artisteSourceId) {

  // Valide la présence des champs obligatoires.
  public ConcertBrut {
    Objects.requireNonNull(artiste, "artiste");
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(salle, "salle");
    Objects.requireNonNull(source, "source");
  }
}
