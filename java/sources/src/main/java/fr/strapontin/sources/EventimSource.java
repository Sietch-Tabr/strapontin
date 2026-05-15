/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/// Client HTTP pour l'API publique Eventim France.
///
/// <p>Effectue les appels REST vers l'API exploration Eventim : recherche par artiste (via
/// attractionId) et par région (par département). La pagination est gérée jusqu'à MAX_PAGES.
///
/// @since 2.0.0
/// @author Kwaite
public class EventimSource {

  private static final Logger log = LoggerFactory.getLogger(EventimSource.class);

  private static final String BASE_URL =
      "https://public-api.eventim.com/websearch/search/api/exploration";
  private static final String WEB_ID = "web__eventim-fr";
  private static final int TAILLE_PAGE = 50;
  private static final int MAX_PAGES = 10;

  private final RestClient restClient;

  /// Crée une instance avec un RestClient par défaut incluant les headers Eventim.
  public EventimSource() {
    this(RestClient.builder()
        .defaultHeader("Accept", "application/json")
        .defaultHeader("User-Agent", "Mozilla/5.0")
        .build());
  }

  /// Crée une instance avec un RestClient personnalisé (injection de test).
  ///
  /// @param restClient client HTTP Spring à utiliser
  public EventimSource(RestClient restClient) {
    this.restClient = restClient;
  }

  /// Extrait le code département depuis un code postal français.
  ///
  /// @param codePostal code postal (peut être null ou vide)
  /// @return code département à 2 ou 3 chiffres, ou null si absent/invalide
  public static String extraireDept(String codePostal) {
    if (codePostal == null || codePostal.isBlank()) return null;
    String cp = codePostal.trim();
    if (cp.startsWith("97")) return cp.substring(0, 3);
    String dept = cp.substring(0, Math.min(2, cp.length()));
    return dept.isEmpty() ? null : dept;
  }

  /// Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
  ///
  /// <p>L'heure "00:00" est considérée comme absente (Eventim utilise minuit par défaut).
  ///
  /// @param dateStr chaîne de date ISO 8601
  /// @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou null]
  public static String[] parseDateEventim(String dateStr) {
    String datePart = dateStr.substring(0, 10);
    int tIndex = dateStr.indexOf('T');
    if (tIndex == -1) return new String[]{datePart, null};
    String heurePart = dateStr.substring(tIndex + 1, Math.min(tIndex + 6, dateStr.length()));
    String heure = "00:00".equals(heurePart) ? null : heurePart;
    return new String[]{datePart, heure};
  }

  /// Extrait le nom de l'artiste depuis un titre d'événement Eventim.
  ///
  /// <p>Le titre Eventim est de la forme "Artiste - Tournée" ou "Artiste : Spectacle".
  /// On extrait la partie avant le premier séparateur " - " ou " : ".
  ///
  /// @param titre titre de l'événement Eventim
  /// @return nom de l'artiste extrait, ou le titre complet si aucun séparateur trouvé
  public static String extraireNomArtiste(String titre) {
    for (String sep : new String[]{" - ", " : "}) {
      int idx = titre.indexOf(sep);
      if (idx > 0) return titre.substring(0, idx).strip();
    }
    return titre;
  }

  /// Mappe un produit Eventim brut (JSON désérialisé) vers un {@link ConcertBrut}.
  ///
  /// @param produit        produit Eventim sous forme de Map imbriquée
  /// @param artisteFallback nom de l'artiste cherché (pour fetchArtiste uniquement), peut être null
  /// @return concert extrait, ou null si le produit est invalide
  @SuppressWarnings("unchecked")
  public static ConcertBrut mapProduit(Map<String, Object> produit, String artisteFallback) {
    try {
      var typeAttributes = (Map<String, Object>) produit.get("typeAttributes");
      var liveEnt = typeAttributes != null
          ? (Map<String, Object>) typeAttributes.get("liveEntertainment")
          : null;

      String dateStr = liveEnt != null ? (String) liveEnt.get("startDate") : null;
      if (dateStr == null) return null;

      String[] datHeure = parseDateEventim(dateStr);
      String date = datHeure[0];
      String heure = datHeure[1];

      String titre = (String) produit.get("name");
      if (titre == null) return null;
      String artiste = artisteFallback != null ? artisteFallback : extraireNomArtiste(titre);

      var location = liveEnt != null ? (Map<String, Object>) liveEnt.get("location") : null;
      String salle = location != null && location.get("name") != null
          ? (String) location.get("name")
          : "Salle inconnue";
      String ville = location != null ? (String) location.get("city") : null;
      String dept = extraireDept(location != null ? (String) location.get("postalCode") : null);

      var priceRange = liveEnt != null ? (Map<String, Object>) liveEnt.get("priceRange") : null;
      Double prixMin = priceRange != null && priceRange.get("min") != null
          ? ((Number) priceRange.get("min")).doubleValue()
          : null;
      Double prixMax = priceRange != null && priceRange.get("max") != null
          ? ((Number) priceRange.get("max")).doubleValue()
          : null;

      var urlObj = (Map<String, Object>) produit.get("url");
      String lienDirect = (String) produit.get("link");
      String url = null;
      if (lienDirect != null && lienDirect.startsWith("http")) {
        url = lienDirect;
      } else if (urlObj != null && urlObj.get("domain") != null && urlObj.get("path") != null) {
        url = urlObj.get("domain") + (String) urlObj.get("path");
      }

      return new ConcertBrut(artiste, date, heure, salle, ville, dept, prixMin, prixMax, url,
          "eventim", null, null, null);
    } catch (Exception e) {
      log.warn("erreur parsing produit Eventim", e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private String rechercherAttractionId(String terme) {
    String uri = UriComponentsBuilder.fromUriString(BASE_URL + "/v1/attractions")
        .queryParam("webId", WEB_ID)
        .queryParam("language", "fr")
        .queryParam("search_term", terme)
        .queryParam("top", 5)
        .build()
        .toUriString();

    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return null;
      var attractions = (List<Map<String, Object>>) data.get("attractions");
      if (attractions == null || attractions.isEmpty()) return null;

      String termeNorm = Normalisation.normaliserTexte(terme);
      Map<String, Object> exact = null;
      for (var a : attractions) {
        String nomNorm = Normalisation.normaliserTexte((String) a.getOrDefault("name", ""));
        if (nomNorm.equals(termeNorm)) {
          exact = a;
          break;
        }
      }
      Map<String, Object> choisi = exact != null ? exact : attractions.get(0);
      Object id = choisi.get("attractionId");
      if (id == null) id = choisi.get("id");
      return id != null ? String.valueOf(id) : null;
    } catch (Exception e) {
      log.warn("Eventim attractions erreur terme={}", terme, e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private List<ConcertBrut> fetchProduitsParAttraction(
      String attractionId, String artisteFallback) {
    LocalDate aujourd_hui = LocalDate.now();
    LocalDate dateFin = aujourd_hui.plusMonths(24);
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    List<ConcertBrut> concerts = new ArrayList<>();

    for (int page = 1; page <= MAX_PAGES; page++) {
      if (page > 1) {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      String uri = UriComponentsBuilder.fromUriString(BASE_URL + "/v1/products")
          .queryParam("webId", WEB_ID)
          .queryParam("language", "fr")
          .queryParam("attraction_ids", attractionId)
          .queryParam("date_from", fmt.format(aujourd_hui))
          .queryParam("date_to", fmt.format(dateFin))
          .queryParam("sort", "DateAsc")
          .queryParam("top", TAILLE_PAGE)
          .queryParam("page", page)
          .build()
          .toUriString();

      try {
        Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
        if (data == null) break;
        var produits = (List<Map<String, Object>>) data.get("products");
        if (produits == null || produits.isEmpty()) break;

        for (var p : produits) {
          ConcertBrut c = mapProduit(p, artisteFallback);
          if (c != null) concerts.add(c);
        }

        int total = data.get("totalResults") != null
            ? ((Number) data.get("totalResults")).intValue()
            : 0;
        if (concerts.size() >= total) break;
      } catch (Exception e) {
        log.error("erreur HTTP Eventim products", e);
        break;
      }
    }

    return concerts;
  }

  @SuppressWarnings("unchecked")
  private List<ConcertBrut> fetchProduitsParRegion(String dept, List<String> classifications) {
    LocalDate aujourd_hui = LocalDate.now();
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    List<ConcertBrut> concerts = new ArrayList<>();

    for (int page = 1; page <= MAX_PAGES; page++) {
      if (page > 1) {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      var builder = UriComponentsBuilder.fromUriString(BASE_URL + "/v1/products")
          .queryParam("webId", WEB_ID)
          .queryParam("language", "fr")
          .queryParam("country", "FR")
          .queryParam("query", dept)
          .queryParam("date_from", fmt.format(aujourd_hui))
          .queryParam("sort", "DateAsc")
          .queryParam("top", TAILLE_PAGE)
          .queryParam("page", page);

      for (String c : classifications) {
        builder = builder.queryParam("categories", c);
      }

      String uri = builder.build().toUriString();

      try {
        Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
        if (data == null) break;
        var produits = (List<Map<String, Object>>) data.get("products");
        if (produits == null || produits.isEmpty()) break;

        String deptFiltrer = dept;
        for (var p : produits) {
          ConcertBrut c = mapProduit(p, null);
          if (c != null && deptFiltrer.equals(c.dept())) concerts.add(c);
        }

        int total = data.get("totalResults") != null
            ? ((Number) data.get("totalResults")).intValue()
            : 0;
        if (concerts.size() >= total) break;
      } catch (Exception e) {
        log.warn("Eventim region erreur dept={}", dept, e);
        break;
      }
    }

    return concerts;
  }

  /// Récupère les concerts d'un artiste via l'API Eventim.
  ///
  /// <p>Recherche d'abord l'attractionId Eventim par le nom, puis pagine les produits.
  ///
  /// @param nom nom de l'artiste
  /// @return liste des concerts trouvés, vide si l'artiste n'est pas référencé sur Eventim
  public List<ConcertBrut> fetchArtiste(String nom) {
    String attractionId = rechercherAttractionId(nom);
    if (attractionId == null) {
      log.info("Eventim: artiste non trouvé artiste={}", nom);
      return List.of();
    }
    List<ConcertBrut> concerts = fetchProduitsParAttraction(attractionId, nom);
    log.info("Eventim fetchArtiste artiste={} total={}", nom, concerts.size());
    return concerts;
  }

  /// Récupère les concerts par région pour une liste de départements.
  ///
  /// @param depts   liste de codes département (ex: ["75", "92"])
  /// @param classifs classifications Eventim à inclure ; par défaut ["music"]
  /// @return liste des concerts trouvés pour tous les départements
  public List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    if (classifs == null) classifs = List.of("music");
    List<ConcertBrut> tous = new ArrayList<>();

    for (String dept : depts) {
      List<ConcertBrut> concerts = fetchProduitsParRegion(dept, classifs);
      tous.addAll(concerts);
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    return tous;
  }
}
