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
 * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut] —
 * extrait de {@link EventimSource} pour séparer le parsing/mapping JSON de la
 * logique du client HTTP.
 */
final class MapeurProduitEventim {

  private static final Logger LOG = LoggerFactory.getLogger(MapeurProduitEventim.class);
  private static final String DEPT_PARIS = "75";
  private static final String VILLE_PARIS = "Paris";
  private static final String SEPARATEUR_NOM_SALLE = " - ";

  private MapeurProduitEventim() {}

  /**
   * Corrige une incohérence connue d'Eventim (salle taguée ville=Paris
   * hors 75) — retombe sur la commune extraite du nom ("Nom - Commune").
   * @param ville ville brute renvoyée par Eventim
   * @param dept département dérivé du code postal, peut être null
   * @param salle nom de la salle, source alternative de la commune
   * @return ville corrigée, l'originale si cohérente, null sinon
   */
  /* package-private : appelé par EventimSource (même package). */
  static String corrigerVilleEventim(final String ville, final String dept, final String salle) {
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

    LOG.warn(
        "Eventim ville incohérente non corrigée salle={} dept={} ville={}", salle, dept, ville);
    return null;
  }

  /**
   * Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
   *
   * <p>L'heure "00:00" est considérée comme absente (minuit par défaut).
   *
   * @param dateStr chaîne de date ISO 8601
   * @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou null]
   */
  /* package-private : appelé par EventimSource (même package). */
  static String[] parseDateEventim(final String dateStr) {
    Objects.requireNonNull(dateStr, "dateStr");
    final String datePart = dateStr.substring(0, 10);
    final int tIndex = dateStr.indexOf('T');
    if (tIndex == -1) {
      return new String[] {datePart, null};
    }
    final String heurePart = dateStr.substring(tIndex + 1, Math.min(tIndex + 6, dateStr.length()));
    final String heure = "00:00".equals(heurePart) ? null : heurePart;
    return new String[] {datePart, heure};
  }

  /**
   * Extrait l'artiste d'un titre Eventim ("Artiste - Tournée"/"Artiste :
   * Spectacle") — normalise les espaces insécables/fines autour du
   * séparateur (détail : CLAUDE.md, Dédoublonnage inter-sources).
   * @param titre titre de l'événement Eventim
   * @return nom de l'artiste extrait, ou le titre complet si non trouvé
   */
  /* package-private : appelé par EventimSource (même package). */
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
   * Mappe un produit Eventim brut (JSON désérialisé) vers un ConcertBrut.
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom recherché (fetchArtiste), null sinon
   * @param artisteSourceId identifiant préfixé (ex. "eventim:12345"), null
   *     si inconnu (fetch région ou salle)
   * @return concert extrait, ou null si le produit est invalide
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par EventimSource (même package). */
  static ConcertBrut mapper(
      final Map<String, Object> produit,
      final String artisteFallback,
      final String artisteSourceId) {
    try {
      final Map<String, Object> liveEnt = extraireLiveEntertainment(produit);
      final String dateStr = (String) liveEnt.get("startDate");
      if (dateStr == null) {
        return null;
      }

      final String titre = (String) produit.get("name");
      if (titre == null) {
        return null;
      }

      final String[] datHeure = parseDateEventim(dateStr);
      final String artiste = artisteFallback != null ? artisteFallback : extraireNomArtiste(titre);
      final Map<String, Object> location = (Map<String, Object>) liveEnt.get("location");
      return construireConcertEventim(
          produit, location, artiste, datHeure[0], datHeure[1], artisteSourceId);
    } catch (Exception e) {
      LOG.warn("erreur parsing produit Eventim", e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extraireLiveEntertainment(final Map<String, Object> produit) {
    final Map<String, Object> typeAttributes = (Map<String, Object>) produit.get("typeAttributes");
    if (typeAttributes == null) {
      return Map.of();
    }
    final Map<String, Object> liveEntertainment =
        (Map<String, Object>) typeAttributes.get("liveEntertainment");
    return liveEntertainment != null ? liveEntertainment : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static String extraireUrlProduit(final Map<String, Object> produit) {
    final String lienDirect = (String) produit.get("link");
    if (lienDirect != null && lienDirect.startsWith("http")) {
      return lienDirect;
    }
    final Map<String, Object> urlObj = (Map<String, Object>) produit.get("url");
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
    // inStock=false couvre à la fois les concerts complets et les préventes :
    // impossible à distinguer via l'API Eventim. On ne stocke pas de statut
    // pour éviter les faux positifs.
    final String statut = null;
    // Eventim n'expose pas de venueId stable : on génère un identifiant interne
    // basé sur le nom normalisé pour alimenter salle_sources et vider la liste
    // admin "Sans source". Écrasable par un vrai venueId si Eventim en expose
    // un à l'avenir (ON CONFLICT DO NOTHING).
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
