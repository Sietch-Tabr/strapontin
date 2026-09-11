/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut] — extrait de {@link
 * EventimSource} pour séparer le parsing/mapping JSON de la logique du client HTTP.
 */
final class MapeurProduitEventim {

  private static final Logger log = LoggerFactory.getLogger(MapeurProduitEventim.class);
  private static final String DEPT_PARIS = "75";
  private static final String VILLE_PARIS = "Paris";
  private static final String SEPARATEUR_NOM_SALLE = " - ";

  private MapeurProduitEventim() {}

  /**
   * Corrige une incohérence connue d'Eventim : certaines salles de banlieue parisienne (ex. Accor
   * Arena, Zénith de Paris) sont taguées ville="Paris" alors que leur code postal n'est pas dans
   * le 75 — on retombe sur la commune extraite du nom de salle Eventim (forme "Nom de la salle -
   * Commune"), quand disponible.
   *
   * @param ville ville brute renvoyée par Eventim
   * @param dept département dérivé du code postal, peut être {@code null}
   * @param salle nom de la salle, utilisé comme source alternative de la commune
   * @return ville corrigée, la ville d'origine si aucune incohérence détectée, ou {@code null} si
   *   une incohérence est détectée sans pouvoir être corrigée — une valeur qu'on vient soi-même de
   *   qualifier d'incohérente ne doit jamais écraser une valeur déjà connue en base ({@code
   *   COALESCE(:ville, ville)} à la persistance)
   */
  static String corrigerVilleEventim(String ville, String dept, String salle) {
    if (ville == null || dept == null) {
      return ville;
    }
    if (DEPT_PARIS.equals(dept) || !VILLE_PARIS.equalsIgnoreCase(ville.trim())) {
      return ville;
    }

    final int idx = salle.lastIndexOf(SEPARATEUR_NOM_SALLE);
    if (idx > 0) {
      final String commune = salle.substring(idx + SEPARATEUR_NOM_SALLE.length()).strip();
      if (!commune.isBlank()) {
        return commune;
      }
    }

    log.warn(
        "Eventim ville incohérente non corrigée salle={} dept={} ville={}", salle, dept, ville);
    return null;
  }

  /**
   * Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
   *
   * L'heure "00:00" est considérée comme absente (Eventim utilise minuit par défaut).
   *
   * @param dateStr chaîne de date ISO 8601
   * @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou null]
   */
  static String[] parseDateEventim(final String dateStr) {
    Objects.requireNonNull(dateStr, "dateStr");
    final String datePart = dateStr.substring(0, 10);
    final int tIndex = dateStr.indexOf('T');
    if (tIndex == -1) {
      return new String[]{datePart, null};
    }
    final String heurePart = dateStr.substring(tIndex + 1, Math.min(tIndex + 6, dateStr.length()));
    final String heure = "00:00".equals(heurePart) ? null : heurePart;
    return new String[] {datePart, heure};
  }

  /**
   * Extrait le nom de l'artiste depuis un titre d'événement Eventim.
   *
   * Le titre Eventim est de la forme "Artiste - Tournée" ou "Artiste : Spectacle". On extrait la
   * partie avant le premier séparateur " - " ou " : ", après normalisation des espaces
   * insécables/fines (U+00A0, U+202F) qu'Eventim place parfois autour du tiret.
   *
   * @param titre titre de l'événement Eventim
   * @return nom de l'artiste extrait, ou le titre complet si aucun séparateur trouvé
   */
  static String extraireNomArtiste(final String titre) {
    Objects.requireNonNull(titre, "titre");
    final String titreNormalise = titre.replace('\u00A0', ' ').replace('\u202F', ' ');
    for (final String sep : new String[] {" - ", " : "}) {
      final int idx = titreNormalise.indexOf(sep);
      if (idx > 0) {
        return titreNormalise.substring(0, idx).strip();
      }
    }
    return titre;
  }

  /**
   * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom de l'artiste cherché (pour fetchArtiste uniquement), peut être null
   * @param artisteSourceId identifiant source préfixé de l'artiste (ex. {@code "eventim:12345"}),
   *   null si inconnu (fetch région ou salle)
   * @return concert extrait, ou null si le produit est invalide
   */
  @SuppressWarnings("unchecked")
  static ConcertBrut mapper(
      final Map<String, Object> produit, final String artisteFallback, final String artisteSourceId) {
    try {
      final var liveEnt = extraireLiveEntertainment(produit);
      final String dateStr = liveEnt != null ? (String) liveEnt.get("startDate") : null;
      if (dateStr == null) {
        return null;
      }

      final String titre = (String) produit.get("name");
      if (titre == null) {
        return null;
      }

      final String[] datHeure = parseDateEventim(dateStr);
      final String artiste = artisteFallback != null ? artisteFallback : extraireNomArtiste(titre);
      final var location = (Map<String, Object>) liveEnt.get("location");
      return construireConcertEventim(
          produit, location, artiste, datHeure[0], datHeure[1], artisteSourceId);
    } catch (Exception e) {
      log.warn("erreur parsing produit Eventim", e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extraireLiveEntertainment(final Map<String, Object> produit) {
    final var typeAttributes = (Map<String, Object>) produit.get("typeAttributes");
    return typeAttributes != null
        ? (Map<String, Object>) typeAttributes.get("liveEntertainment")
        : null;
  }

  @SuppressWarnings("unchecked")
  private static String extraireUrlProduit(final Map<String, Object> produit) {
    final String lienDirect = (String) produit.get("link");
    if (lienDirect != null && lienDirect.startsWith("http")) {
      return lienDirect;
    }
    final var urlObj = (Map<String, Object>) produit.get("url");
    if (urlObj != null && urlObj.get("domain") != null && urlObj.get("path") != null) {
      return urlObj.get("domain") + (String) urlObj.get("path");
    }
    return null;
  }

  private static ConcertBrut construireConcertEventim(
      final Map<String, Object> produit,
      final Map<String, Object> location,
      final String artiste,
      final String date,
      final String heure,
      final String artisteSourceId) {
    final String salle =
        location != null && location.get("name") != null
            ? Normalisation.normaliserCasse((String) location.get("name"))
            : "Salle inconnue";
    String ville = location != null ? (String) location.get("city") : null;
    final String dept =
        Normalisation.extraireDeptFrancais(
            location != null ? (String) location.get("postalCode") : null);
    ville = corrigerVilleEventim(ville, dept, salle);

    final String url = extraireUrlProduit(produit);
    // inStock=false couvre à la fois les concerts complets et les préventes : impossible à
    // distinguer via l'API Eventim. On ne stocke pas de statut pour éviter les faux positifs.
    final String statut = null;
    // Eventim n'expose pas de venueId stable : on génère un identifiant interne basé sur
    // le nom normalisé pour alimenter salle_sources et vider la liste admin "Sans source".
    // Écrasable par un vrai venueId si Eventim en expose un à l'avenir (ON CONFLICT DO NOTHING).
    final String salleNorm = Normalisation.normaliserTexte(salle);
    final boolean salleInconnue = "Salle inconnue".equals(salle);
    final String salleSourceId =
        (salleNorm.isBlank() || salleInconnue) ? null : "eventim:internal:" + salleNorm;
    return new ConcertBrut(
        artiste,
        date,
        heure,
        salle,
        ville,
        dept,
        url,
        "eventim",
        null,
        null,
        statut,
        null,
        null,
        salleSourceId,
        artisteSourceId);
  }
}
