/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mappe un événement Ticketmaster brut (JSON désérialisé) vers un [ConcertBrut] — extrait de
 * {@link TicketmasterSource} pour séparer le parsing/mapping JSON de la logique du client HTTP.
 */
final class MapeurEvenementTM {

  private static final Logger LOG = LoggerFactory.getLogger(MapeurEvenementTM.class);
  private static final String CLE_EMBEDDED = "_embedded";

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
   * Extrait la date et l'heure d'un événement TM depuis les champs localDate / localTime.
   *
   * L'heure "00:00" est considérée comme absente — TM utilise minuit par défaut pour les
   * événements sans heure précise, symétrique au comportement d'Eventim.
   *
   * @param dateStr valeur de localDate (format ISO 8601, au moins 10 chars)
   * @param heureStr valeur de localTime (peut être null ou "00:00:00" si non définie)
   * @return tableau à deux éléments : [date, heure] où heure peut être null
   */
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
   * @param artisteFallback nom de l'artiste cherché (pour fetch:artiste uniquement), peut être
   *   null
   * @return concert extrait, ou null si l'événement est invalide ou hors périmètre
   */
  static ConcertBrut mapper(final Map<String, Object> evt, final String artisteFallback) {
    try {
      return parseEvenementTM(evt, artisteFallback);
    } catch (Exception e) {
      LOG.warn("erreur parsing événement TM", e);
      return null;
    }
  }

  /**
   * Indique si un horodatage ISO 8601 (avec offset ou {@code Z}) est dans le futur.
   *
   * @param iso horodatage à analyser
   * @return {@code true} si postérieur à maintenant, {@code false} si passé ou illisible
   */
  private static boolean estDansLeFutur(final String iso) {
    try {
      return OffsetDateTime.parse(iso).toInstant().isAfter(Instant.now());
    } catch (Exception e) {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private static String extraireArtisteTM(
      final Map<String, Object> evt,
      final Map<String, Object> embedded,
      final String artisteFallback) {
    final List<Map<String, Object>> attractions =
        embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
    final String nomAttraction =
        attractions != null && !attractions.isEmpty()
            ? (String) attractions.get(0).get("name")
            : null;
    if (nomAttraction != null) {
      // Si la recherche était par nom d'artiste, rejeter les attractions dont le nom normalisé
      // ne correspond pas exactement (évite les homonymes partiels, ex: "Anuv Jain" pour "Jain").
      if (artisteFallback != null
          && !Normalisation.normaliserTexte(nomAttraction)
              .equals(Normalisation.normaliserTexte(artisteFallback))) {
        return null;
      }
      return Normalisation.normaliserCasse(nomAttraction);
    }
    if (artisteFallback == null) {
      return null;
    }
    final String nomEvenement = (String) evt.get("name");
    if (nomEvenement != null
        && nomEvenement.toLowerCase().contains(artisteFallback.toLowerCase())) {
      return artisteFallback;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static String extraireAttractionIdTM(final Map<String, Object> embedded) {
    final List<Map<String, Object>> attractions =
        embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
    if (attractions == null || attractions.isEmpty()) {
      return null;
    }
    final Object id = attractions.get(0).get("id");
    return id != null ? "ticketmaster:" + id : null;
  }

  @SuppressWarnings("unchecked")
  private static String[] extraireClassificationTM(
      final List<Map<String, Object>> classificationsData) {
    if (classificationsData == null || classificationsData.isEmpty()) {
      return new String[] {null, null};
    }
    final Map<String, String> segment =
        (Map<String, String>) classificationsData.get(0).get("segment");
    final String segmentNom =
        segment != null && segment.get("name") != null ? segment.get("name").toLowerCase() : null;
    final Map<String, String> genreMap =
        (Map<String, String>) classificationsData.get(0).get("genre");
    final String genre = genreMap != null ? genreMap.get("name") : null;
    return new String[] {segmentNom, genre};
  }

  private record InfosVenueTM(String salle, String ville, String dept, String salleSourceId) {}

  @SuppressWarnings("unchecked")
  private static InfosVenueTM extraireInfosVenueTM(final Map<String, Object> venue) {
    final String salle =
        Normalisation.normaliserCasse(
            venue != null && venue.get("name") != null
                ? (String) venue.get("name")
                : "Salle inconnue");
    final Map<String, String> cityMap =
        venue != null ? (Map<String, String>) venue.get("city") : null;
    final String ville = cityMap != null ? cityMap.get("name") : null;
    // Un code postal étranger dont les deux premiers chiffres collisionnent avec un département
    // français existant (ex. Luxembourg 43xx / Haute-Loire 43) produirait sinon un dept français
    // plausible mais faux — countryCode absent traité comme française par défaut (comportement
    // historique, aucun cas contraire connu).
    final Map<String, String> countryMap =
        venue != null ? (Map<String, String>) venue.get("country") : null;
    final String countryCode = countryMap != null ? countryMap.get("countryCode") : null;
    final String dept =
        countryCode == null || "FR".equals(countryCode)
            ? Normalisation.extraireDeptFrancais(
                venue != null ? (String) venue.get("postalCode") : null)
            : null;
    final Object venueIdObj = venue != null ? venue.get("id") : null;
    final String salleSourceId = venueIdObj != null ? "ticketmaster:" + venueIdObj : null;
    return new InfosVenueTM(salle, ville, dept, salleSourceId);
  }

  private record InfosVenteTM(String statut, String presaleDebut, String presaleNom) {}

  // TM n'expose aucun champ documenté distinguant un event "package" (offre VIP/bundle, ex.
  // package-angele-.../idpack/...) d'un event standard — repéré uniquement par ce segment d'URL,
  // constant sur tous les cas observés en production. Un package n'est pas accessible à un achat
  // de billetterie standard : son statut de vente ne doit jamais être notifié comme une prévente.
  private static final Pattern URL_PACKAGE_TM = Pattern.compile("(?i)/pack/");

  private static boolean estEvenementPackage(final String url) {
    return url != null && URL_PACKAGE_TM.matcher(url).find();
  }

  // "offsale" recouvre deux situations distinctes chez TM : un concert réellement complet, ou
  // une vente qui n'a simplement pas encore ouvert (prévente à venir, ou vente publique future) —
  // seule la seconde doit être requalifiée en "presale" plutôt que rester "offsale" à tort.
  @SuppressWarnings("unchecked")
  private static InfosVenteTM extraireInfosVenteTM(
      final Map<String, Object> dates, final Map<String, Object> evt) {
    final Map<String, String> statusMap = (Map<String, String>) dates.get("status");
    String statut = statusMap != null ? statusMap.get("code") : null;

    final Map<String, Object> sales = (Map<String, Object>) evt.get("sales");
    final List<Map<String, Object>> presales =
        sales != null ? (List<Map<String, Object>>) sales.get("presales") : null;
    final Map<String, Object> presale =
        presales != null && !presales.isEmpty() ? presales.get(0) : null;
    String presaleDebut = presale != null ? (String) presale.get("startDateTime") : null;
    final String presaleNom = presale != null ? (String) presale.get("name") : null;

    if ("offsale".equals(statut)) {
      final Map<String, Object> ventePublique =
          sales != null ? (Map<String, Object>) sales.get("public") : null;
      final String ouverturePublique =
          ventePublique != null ? (String) ventePublique.get("startDateTime") : null;
      if (presaleDebut != null && estDansLeFutur(presaleDebut)) {
        statut = "presale";
      } else if (ouverturePublique != null && estDansLeFutur(ouverturePublique)) {
        statut = "presale";
        presaleDebut = ouverturePublique;
      }
    }
    return new InfosVenteTM(statut, presaleDebut, presaleNom);
  }

  @SuppressWarnings("unchecked")
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
    final String artiste = extraireArtisteTM(evt, embedded, artisteFallback);
    if (artiste == null) {
      return null;
    }

    final List<Map<String, Object>> venues =
        embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
    final Map<String, Object> venue = venues != null && !venues.isEmpty() ? venues.get(0) : null;
    final InfosVenueTM infosVenue = extraireInfosVenueTM(venue);
    final String artisteSourceId = extraireAttractionIdTM(embedded);

    final String url = (String) evt.get("url");
    final InfosVenteTM infosVente =
        estEvenementPackage(url)
            ? new InfosVenteTM(null, null, null)
            : extraireInfosVenteTM(dates, evt);

    final List<Map<String, Object>> classificationsData =
        (List<Map<String, Object>>) evt.get("classifications");
    final String[] segmentGenre = extraireClassificationTM(classificationsData);

    return new ConcertBrut(
        artiste,
        datHeure[0],
        datHeure[1],
        infosVenue.salle(),
        infosVenue.ville(),
        infosVenue.dept(),
        url,
        "ticketmaster",
        normaliserClassification(segmentGenre[0]),
        segmentGenre[1],
        infosVente.statut(),
        infosVente.presaleDebut(),
        infosVente.presaleNom(),
        infosVenue.salleSourceId(),
        artisteSourceId);
  }
}
