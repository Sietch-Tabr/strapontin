/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Concert brut tel que renvoyé par une source (Ticketmaster, Eventim, Digitick), avant
 * dédoublonnage et résolution des identifiants du référentiel ({@code salleId}/{@code artisteId}).
 *
 * @param artiste nom de l'artiste tel que fourni par la source (non normalisé)
 * @param date date du concert au format {@code yyyy-MM-dd}
 * @param heure heure du concert au format {@code HH:mm}, ou {@code null} si non renseignée par la
 *     source
 * @param salle nom de la salle tel que fourni par la source (non normalisé)
 * @param ville ville de la salle, ou {@code null} si non renseignée par la source
 * @param dept code département à 2 ou 3 chiffres, ou {@code null} si non déductible du code postal
 *     source
 * @param url URL de la page événement sur le site source, ou {@code null} si non fournie
 * @param source identifiant de la source ({@code "ticketmaster"}, {@code "eventim"} ou {@code
 *     "digitick"})
 * @param classification catégorie de l'événement fournie par la source (ex. {@code "music"}, {@code
 *     "theatre"}), ou {@code null} si non classifiée
 * @param genre genre musical/artistique fourni par la source, ou {@code null} si absent
 * @param statut statut de vente fourni par la source (ex. en vente, complet), ou {@code null} si
 *     absent
 * @param presaleDebut date de début de prévente, ou {@code null} si aucune prévente
 * @param presaleNom nom de la prévente, ou {@code null} si aucune prévente
 * @param salleSourceId identifiant externe de la salle chez la source, ou {@code null} si la source
 *     ne fournit pas d'identifiant stable
 * @param artisteSourceId identifiant externe de l'artiste chez la source, ou {@code null} si la
 *     source ne fournit pas d'identifiant stable
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
