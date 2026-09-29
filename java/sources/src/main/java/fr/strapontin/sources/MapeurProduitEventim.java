/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut] —
 * extrait de {@link EventimSource} pour séparer le parsing/mapping JSON de la
 * logique du client HTTP.
 */
final class MapeurProduitEventim {

  private static final Logger LOG = LoggerFactory.getLogger(MapeurProduitEventim.class);
  private static final String CLE_NOM = "name";
  private static final String DEPT_PARIS = "75";
  private static final String VILLE_PARIS = "Paris";
  private static final String SEPARATEUR_NOM_SALLE = " - ";
  // « Paris 07 » : arrondissement accolé par Eventim, jamais par Ticketmaster.
  private static final Pattern VILLE_ARRONDISSEMENT =
      Pattern.compile("^(Paris|Lyon|Marseille) \\d{1,2}$", Pattern.CASE_INSENSITIVE);
  // Signalée une fois par démarrage, sinon répétée à chaque produit du lieu.
  private static final Set<String> SALLES_VILLE_NON_CORRIGEE = ConcurrentHashMap.newKeySet();

  private MapeurProduitEventim() {}

  /**
   * Corrige « Paris 07 » en « Paris », et une salle taguée Paris hors 75
   * (commune extraite du nom "Nom - Commune").
   * @param ville ville brute renvoyée par Eventim
   * @param dept département dérivé du code postal, peut être null
   * @param salle nom de la salle, source alternative de la commune
   * @return ville corrigée, l'originale si cohérente, null sinon
   */
  /* package-private : appelé par EventimSource (même package). */
  static String corrigerVilleEventim(final String ville, final String dept, final String salle) {
    if (ville == null) {
      return null;
    }
    final Matcher arrondissement = VILLE_ARRONDISSEMENT.matcher(ville.strip());
    if (arrondissement.matches()) {
      return arrondissement.group(1);
    }
    if (dept == null) {
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

    if (SALLES_VILLE_NON_CORRIGEE.add(salle)) {
      LOG.warn(
          "Eventim ville incohérente non corrigée salle={} dept={} ville={}", salle, dept, ville);
    }
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

      final String titre = (String) produit.get(CLE_NOM);
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

  /**
   * Extrait le lieu d'un produit Eventim (libellé, ville, coordonnées), avec
   * le même identifiant interne que celui du concert correspondant.
   * @param produit produit Eventim sous forme de Map imbriquée
   * @return lieu, ou null si le produit n'en porte pas d'exploitable
   */
  /* package-private : appelé par EventimSource (même package). */
  static LieuEventim extraireLieu(final Map<String, Object> produit) {
    try {
      if (!(extraireLiveEntertainment(produit).get("location") instanceof Map<?, ?> location)
          || !(location.get(CLE_NOM) instanceof String libelle)) {
        return null;
      }
      final String salleNorm =
          Normalisation.normaliserTexte(Normalisation.normaliserCasse(libelle));
      if (salleNorm.isBlank()) {
        return null;
      }
      final Map<?, ?> geo =
          location.get("geoLocation") instanceof Map<?, ?> coordonnees ? coordonnees : Map.of();
      return new LieuEventim(
          "internal:" + salleNorm,
          libelle,
          location.get("city") instanceof String ville ? ville : null,
          geo.get("latitude") instanceof Number lat ? lat.doubleValue() : null,
          geo.get("longitude") instanceof Number lon ? lon.doubleValue() : null);
    } catch (Exception e) {
      LOG.warn("lieu Eventim illisible : {}", e.getMessage());
      return null;
    }
  }

  private static ConcertBrut construireConcertEventim(
      final Map<String, Object> produit,
      final Map<String, Object> location,
      final String artiste,
      final String date,
      final String heure,
      final String artisteSourceId) {
    final String salle =
        location != null && location.get(CLE_NOM) != null
            ? Normalisation.normaliserCasse((String) location.get(CLE_NOM))
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
