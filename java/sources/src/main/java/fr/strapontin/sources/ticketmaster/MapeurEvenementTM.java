/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.ticketmaster;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.PlateauSoiree;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mappe un événement Ticketmaster brut (JSON désérialisé) vers un [ConcertBrut]
 * — extrait de {@link TicketmasterSource} pour séparer le parsing/mapping JSON
 * de la logique du client HTTP.
 */
final class MapeurEvenementTM {

  private static final Logger LOG = LoggerFactory.getLogger(MapeurEvenementTM.class);
  private static final String CLE_EMBEDDED = "_embedded";
  private static final String CLE_NAME = "name";
  private static final String UNCHECKED = "unchecked";

  private static final Map<String, String> CLASSIFICATION_MAP =
      Map.of(
          "musique",
          "music",
          "opéra",
          "opera",
          "comédie musicale",
          "musical",
          "arts et théâtre",
          "theatre");

  private MapeurEvenementTM() {}

  /**
   * Extrait la date et l'heure d'un événement TM depuis localDate /
   * localTime — l'heure "00:00" est considérée absente (minuit par
   * défaut, symétrique à Eventim).
   * @param dateStr valeur de localDate (format ISO 8601, au moins 10 chars)
   * @param heureStr valeur de localTime (null ou "00:00:00" si absente)
   * @return tableau [date, heure] où heure peut être null
   */
  /* package-private : visibilité élargie pour les tests du même package. */
  static String[] parseDateTM(final String dateStr, final String heureStr) {
    Objects.requireNonNull(dateStr, "dateStr");
    final String date = dateStr.substring(0, 10);
    if (heureStr == null) {
      return new String[] {date, null};
    }
    final String heure = heureStr.substring(0, 5);
    return new String[] {date, "00:00".equals(heure) ? null : heure};
  }

  private static String normaliserClassification(final String nom) {
    if (nom == null) {
      return null;
    }
    return CLASSIFICATION_MAP.getOrDefault(nom, nom);
  }

  /**
   * Mappe un événement TM brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param evt événement TM sous forme de Map imbriquée
   * @param artisteFallback nom recherché (fetch:artiste), null sinon
   * @return concert extrait, ou null si l'événement est invalide ou hors périmètre
   */
  /* package-private : appelé par TicketmasterSource (même package). */
  static ConcertBrut mapper(final Map<String, Object> evt, final String artisteFallback) {
    try {
      return parseEvenementTM(evt, artisteFallback);
    } catch (Exception e) {
      LOG.warn("erreur parsing événement TM", e);
      return null;
    }
  }

  @SuppressWarnings(UNCHECKED)
  private static List<Map<String, Object>> attractions(final Map<String, Object> embedded) {
    final List<Map<String, Object>> attractions =
        embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
    return attractions != null ? attractions : List.of();
  }

  /**
   * Attraction retenue comme artiste : celle qu'une recherche par artiste
   * désigne exactement (un co-plateau peut la citer en second ; un homonyme
   * partiel, « Anuv Jain » pour « Jain », reste écarté), sinon la première.
   * @return l'attraction, vide si aucune ne convient
   */
  private static Optional<Map<String, Object>> attractionPrincipale(
      final List<Map<String, Object>> attractions, final String artisteFallback) {
    if (artisteFallback == null) {
      return attractions.stream().findFirst();
    }
    final String recherche = Normalisation.normaliserTexte(artisteFallback);
    return attractions.stream()
        .filter(a -> a.get(CLE_NAME) instanceof String)
        .filter(a -> Normalisation.normaliserTexte((String) a.get(CLE_NAME)).equals(recherche))
        .findFirst();
  }

  private static String extraireArtisteTM(
      final Map<String, Object> evt,
      final List<Map<String, Object>> attractions,
      final Map<String, Object> principale,
      final String artisteFallback) {
    if (principale != null && principale.get(CLE_NAME) instanceof String nom) {
      return Normalisation.normaliserCasse(nom);
    }
    if (!attractions.isEmpty()) {
      return null;
    }
    return artisteDepuisFallback(evt, artisteFallback);
  }

  private static String artisteDepuisFallback(
      final Map<String, Object> evt, final String artisteFallback) {
    if (artisteFallback == null) {
      return null;
    }
    final String nomEvenement = (String) evt.get(CLE_NAME);
    if (nomEvenement != null
        && nomEvenement
            .toLowerCase(Locale.ROOT)
            .contains(artisteFallback.toLowerCase(Locale.ROOT))) {
      return artisteFallback;
    }
    return null;
  }

  @SuppressWarnings(UNCHECKED)
  private static ExtracteurVenueTM.InfosVenueTM infosVenue(final Map<String, Object> embedded) {
    final List<Map<String, Object>> venues =
        embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
    final Map<String, Object> venue = venues != null && !venues.isEmpty() ? venues.get(0) : null;
    return ExtracteurVenueTM.extraireInfosVenueTM(venue);
  }

  private static String extraireAttractionIdTM(final Map<String, Object> principale) {
    final Object id = principale != null ? principale.get("id") : null;
    return id != null ? "ticketmaster:" + id : null;
  }

  // Les autres attractions de l'événement : le plateau d'une soirée partagée.
  private static List<String> invitesTM(
      final List<Map<String, Object>> attractions, final String artiste) {
    final List<String> noms = new ArrayList<>();
    for (final Map<String, Object> attraction : attractions) {
      if (attraction.get(CLE_NAME) instanceof String nom) {
        noms.add(Normalisation.normaliserCasse(nom));
      }
    }
    return PlateauSoiree.sansArtistePrincipal(noms, artiste);
  }

  @SuppressWarnings(UNCHECKED)
  private static String[] extraireClassificationTM(
      final List<Map<String, Object>> classificationsData) {
    if (classificationsData == null || classificationsData.isEmpty()) {
      return new String[] {null, null, null};
    }
    final Map<String, String> segment =
        (Map<String, String>) classificationsData.get(0).get("segment");
    final String segmentNom =
        segment != null && segment.get(CLE_NAME) != null
            ? segment.get(CLE_NAME).toLowerCase(Locale.ROOT)
            : null;
    final Map<String, String> genreMap =
        (Map<String, String>) classificationsData.get(0).get("genre");
    final String genre = genreMap != null ? genreMap.get(CLE_NAME) : null;
    final Map<String, String> sousGenreMap =
        (Map<String, String>) classificationsData.get(0).get("subGenre");
    final String sousGenre = sousGenreMap != null ? sousGenreMap.get(CLE_NAME) : null;
    return new String[] {segmentNom, genre, sousGenre};
  }

  @SuppressWarnings(UNCHECKED)
  private static ConcertBrut parseEvenementTM(
      final Map<String, Object> evt, final String artisteFallback) {
    final Map<String, Object> dates = (Map<String, Object>) evt.get("dates");
    final Map<String, Object> start =
        dates != null ? (Map<String, Object>) dates.get("start") : null;
    final String dateStr = start != null ? (String) start.get("localDate") : null;
    if (dateStr == null) {
      return null;
    }

    final String[] datHeure = parseDateTM(dateStr, (String) start.get("localTime"));

    final Map<String, Object> embedded = (Map<String, Object>) evt.get(CLE_EMBEDDED);
    final List<Map<String, Object>> attractions = attractions(embedded);
    final Map<String, Object> principale =
        attractionPrincipale(attractions, artisteFallback).orElse(null);
    final String artiste = extraireArtisteTM(evt, attractions, principale, artisteFallback);
    if (artiste == null) {
      return null;
    }

    final ExtracteurVenueTM.InfosVenueTM infosVenue = infosVenue(embedded);
    final String artisteSourceId = extraireAttractionIdTM(principale);

    final String url = (String) evt.get("url");
    final ExtracteurVenteTM.InfosVenteTM infosVente =
        ExtracteurVenteTM.estEvenementPackage(url)
            ? new ExtracteurVenteTM.InfosVenteTM(null, null, null)
            : ExtracteurVenteTM.extraireInfosVenteTM(dates, evt);

    final List<Map<String, Object>> classificationsData =
        (List<Map<String, Object>>) evt.get("classifications");
    final String[] segmentGenre = extraireClassificationTM(classificationsData);

    return ConcertBrut.nouveau()
        .artiste(artiste)
        .date(datHeure[0])
        .heure(datHeure[1])
        .salle(infosVenue.salle())
        .ville(infosVenue.ville())
        .dept(infosVenue.dept())
        .url(url)
        .source("ticketmaster")
        .classification(normaliserClassification(segmentGenre[0]))
        .genre(segmentGenre[1])
        .sousGenre(segmentGenre[2])
        .statut(infosVente.statut())
        .presaleDebut(infosVente.presaleDebut())
        .presaleNom(infosVente.presaleNom())
        .salleSourceId(infosVenue.salleSourceId())
        .artisteSourceId(artisteSourceId)
        .position(infosVenue.position())
        .artistesInvites(invitesTM(attractions, artiste))
        .construire();
  }
}
