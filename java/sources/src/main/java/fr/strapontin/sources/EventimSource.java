/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.time.Duration;
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
/// Effectue les appels REST vers l'API exploration Eventim : recherche par artiste (via
/// attractionId) et par région (par département). La pagination est gérée jusqu'à MAX_PAGES.
///
/// @since 2.0.0
/// @author Kwaite
public class EventimSource implements SourceConcerts, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(EventimSource.class);

  private static final String BASE_URL =
      "https://public-api.eventim.com/websearch/search/api/exploration";
  private static final String WEB_ID = "web__eventim-fr";
  private static final String PARAM_WEB_ID = "webId";
  private static final String PARAM_LANGUE = "language";
  private static final String CLE_PRODUCTS = "products";
  private static final String CLE_TOTAL_RESULTS = "totalResults";
  private static final int TAILLE_PAGE = 50;
  private static final int MAX_PAGES = 10;
  private static final String DEPT_PARIS = "75";
  private static final String VILLE_PARIS = "Paris";
  private static final String SEPARATEUR_NOM_SALLE = " - ";

  /// Catégories Eventim correspondant aux spectacles musicaux (concerts et musique classique).
  public static final List<String> CATEGORIES_REGION =
      List.of("Concerts & Festivals", "Classique & Danse");

  /// `User-Agent` honnête de bot (#904) — jamais une chaîne `Mozilla/5.0...`, voir Javadoc du
  /// constructeur par défaut pour la justification complète.
  static final String USER_AGENT = "StrapontinBot/1.0 (+https://strapontin.kwaite.fr)";

  private final RestClient restClient;
  // Compteur cumulé depuis le dernier prélèvement (remonté dans logs_admin par le worker, #901,
  // factorisé en composant partagé par #909).
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();
  // #1013 : correspondances par containment acceptées depuis le dernier prélèvement — un
  // containment n'est pas nécessairement une erreur (ex. "-M-" → "-M- Matthieu Chedid" est
  // correct) mais reste un signal à vérifier manuellement, jusque-là invisible (log SLF4J niveau
  // INFO uniquement, jamais dans logs_admin).
  private final List<String> containmentsAcceptes =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  /// Crée une instance avec un RestClient par défaut incluant les headers Eventim et un timeout
  /// explicite (connexion 10s, lecture 30s — #887).
  ///
  /// <p>Le `User-Agent` identifie honnêtement un bot plutôt que de mimer un navigateur (#904) :
  /// depuis le 09/07/2026, Akamai bloque en 403 tout client dont le `User-Agent` prétend être un
  /// navigateur (`Mozilla/5.0...`, quel que soit le navigateur imité) sans en avoir la signature
  /// réelle, alors qu'un `User-Agent` non-navigateur passe sans restriction — vérifié par tests
  /// répétés depuis des réseaux distincts. Les en-têtes `sec-ch-ua*`/`Sec-Fetch-*`/`Referer`/
  /// `Origin` (contournement anti-bot historique, commit 231c026, devenu obsolète) sont retirés
  /// en conséquence : incohérents avec un `User-Agent` de bot.
  ///
  /// <p>Aucun `Accept-Encoding` explicite : envoyer ce header manuellement fait basculer
  /// `SimpleClientHttpRequestFactory` (JDK `HttpURLConnection`, introduit par #887) en mode
  /// « décompression à la charge de l'appelant » — Eventim répond alors en gzip sans jamais être
  /// décompressé, faisant échouer la désérialisation JSON. En l'absence de ce header, le JDK ajoute
  /// son propre `Accept-Encoding: gzip` et décompresse la réponse de façon transparente. Bug latent
  /// introduit par #887 le 08/07/2026, masqué du 09/07 au correctif #904 par le blocage Akamai.
  public EventimSource() {
    this(
        builderAvecHeadersParDefaut()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build());
  }

  /// Construit un {@link RestClient.Builder} avec les headers par défaut (sans requestFactory ni
  /// build), pour permettre à {@link org.springframework.test.web.client.MockRestServiceServer}
  /// de se lier à la configuration réelle en test (#904 — régression sur `User-Agent`/
  /// `Accept-Encoding`).
  ///
  /// @return le builder configuré, prêt à recevoir une requestFactory
  static RestClient.Builder builderAvecHeadersParDefaut() {
    return RestClient.builder()
        .defaultHeader("Accept", "application/json, text/plain, */*")
        .defaultHeader("User-Agent", USER_AGENT)
        .defaultHeader("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
        .defaultHeader("Connection", "keep-alive");
  }

  /// Crée une instance avec un RestClient personnalisé (injection de test).
  ///
  /// @param restClient client HTTP Spring à utiliser
  public EventimSource(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String nom() {
    return "eventim";
  }

  /// Retourne le bilan agrégé des appels HTTP Eventim depuis le dernier prélèvement (#901) et
  /// remet les compteurs à zéro.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /// Retourne les correspondances par containment acceptées depuis le dernier prélèvement (#1013)
  /// et vide la liste — contrairement à {@link #preleverBilan}, ce n'est pas un bilan de panne :
  /// Eventim ne persiste aucun identifiant ({@link #rechercherAttractionId} est rappelé à chaque
  /// cycle), donc un containment mal résolu peut réapparaître à chaque fetch tant qu'il n'est pas
  /// corrigé manuellement.
  ///
  /// @return messages décrivant chaque containment accepté depuis le dernier prélèvement
  public List<String> preleverContainmentsAcceptes() {
    synchronized (containmentsAcceptes) {
      List<String> copie = List.copyOf(containmentsAcceptes);
      containmentsAcceptes.clear();
      return copie;
    }
  }

  /// Corrige la ville Eventim pour les salles de la région parisienne hors Paris intra-muros.
  ///
  /// Eventim utilise parfois "Paris" comme libellé régional/marketing au lieu de la commune
  /// réelle pour les salles de la petite couronne (ex. La Seine Musicale à Boulogne-Billancourt,
  /// Stade de France à Saint-Denis), alors que `dept` (dérivé du code postal) reste fiable.
  /// Si une incohérence est détectée, tente d'extraire la commune depuis le nom de la salle
  /// (forme "Salle - Commune") ; sinon journalise et conserve la ville telle que reçue.
  ///
  /// @param ville ville brute renvoyée par Eventim, peut être `null`
  /// @param dept  département dérivé du code postal, peut être `null`
  /// @param salle nom de la salle, utilisé comme source alternative de la commune
  /// @return ville corrigée, ou la ville d'origine si aucune incohérence ou correction possible
  public static String corrigerVilleEventim(String ville, String dept, String salle) {
    if (ville == null || dept == null) return ville;
    if (DEPT_PARIS.equals(dept) || !VILLE_PARIS.equalsIgnoreCase(ville.trim())) return ville;

    int idx = salle.lastIndexOf(SEPARATEUR_NOM_SALLE);
    if (idx > 0) {
      String commune = salle.substring(idx + SEPARATEUR_NOM_SALLE.length()).strip();
      if (!commune.isBlank()) return commune;
    }

    log.warn(
        "Eventim ville incohérente non corrigée salle={} dept={} ville={}", salle, dept, ville);
    return ville;
  }

  /// Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
  ///
  /// L'heure "00:00" est considérée comme absente (Eventim utilise minuit par défaut).
  ///
  /// @param dateStr chaîne de date ISO 8601
  /// @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou null]
  public static String[] parseDateEventim(String dateStr) {
    String datePart = dateStr.substring(0, 10);
    int tIndex = dateStr.indexOf('T');
    if (tIndex == -1) return new String[] {datePart, null};
    String heurePart = dateStr.substring(tIndex + 1, Math.min(tIndex + 6, dateStr.length()));
    String heure = "00:00".equals(heurePart) ? null : heurePart;
    return new String[] {datePart, heure};
  }

  /// Extrait le nom de l'artiste depuis un titre d'événement Eventim.
  ///
  /// Le titre Eventim est de la forme "Artiste - Tournée" ou "Artiste : Spectacle".
  /// On extrait la partie avant le premier séparateur " - " ou " : ".
  ///
  /// @param titre titre de l'événement Eventim
  /// @return nom de l'artiste extrait, ou le titre complet si aucun séparateur trouvé
  public static String extraireNomArtiste(String titre) {
    for (String sep : new String[] {" - ", " : "}) {
      int idx = titre.indexOf(sep);
      if (idx > 0) return titre.substring(0, idx).strip();
    }
    return titre;
  }

  /// Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
  ///
  /// @param produit         produit Eventim sous forme de Map imbriquée
  /// @param artisteFallback nom de l'artiste cherché (pour fetchArtiste uniquement), peut être null
  /// @return concert extrait, ou null si le produit est invalide
  public static ConcertBrut mapProduit(Map<String, Object> produit, String artisteFallback) {
    return mapProduit(produit, artisteFallback, null);
  }

  /// Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
  ///
  /// @param produit          produit Eventim sous forme de Map imbriquée
  /// @param artisteFallback  nom de l'artiste cherché (pour fetchArtiste uniquement), peut être
  // null
  /// @param artisteSourceId  identifiant source préfixé de l'artiste (ex. {@code "eventim:12345"}),
  ///                         null si inconnu (fetch région ou salle)
  /// @return concert extrait, ou null si le produit est invalide
  @SuppressWarnings("unchecked")
  public static ConcertBrut mapProduit(
      Map<String, Object> produit, String artisteFallback, String artisteSourceId) {
    try {
      var liveEnt = extraireLiveEntertainment(produit);
      String dateStr = liveEnt != null ? (String) liveEnt.get("startDate") : null;
      if (dateStr == null) return null;

      String titre = (String) produit.get("name");
      if (titre == null) return null;

      String[] datHeure = parseDateEventim(dateStr);
      String artiste = artisteFallback != null ? artisteFallback : extraireNomArtiste(titre);
      @SuppressWarnings("unchecked")
      var location = (Map<String, Object>) liveEnt.get("location");
      return construireConcertEventim(
          produit, liveEnt, location, artiste, datHeure[0], datHeure[1], artisteSourceId);
    } catch (Exception e) {
      log.warn("erreur parsing produit Eventim", e);
      return null;
    }
  }

  private record ResultatPageEventim(List<ConcertBrut> concerts, int total) {
    static final ResultatPageEventim VIDE = new ResultatPageEventim(List.of(), 0);

    boolean estVide() {
      return concerts.isEmpty();
    }
  }

  private boolean attendreEntrePagesEventim() {
    try {
      Thread.sleep(200);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private ResultatPageEventim chargerPageProduits(
      String uri, String artisteFallback, String deptFiltre) {
    return chargerPageProduits(uri, artisteFallback, deptFiltre, null);
  }

  @SuppressWarnings("unchecked")
  private ResultatPageEventim chargerPageProduits(
      String uri, String artisteFallback, String deptFiltre, String artisteSourceId) {
    compteurBilan.enregistrerAppel();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return parserReponseProduits(data, artisteFallback, deptFiltre, artisteSourceId);
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Eventim erreur HTTP", e);
      return ResultatPageEventim.VIDE;
    }
  }

  @SuppressWarnings("unchecked")
  private ResultatPageEventim chargerPageProduits(
      java.net.URI uri, String artisteFallback, String deptFiltre) {
    compteurBilan.enregistrerAppel();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return parserReponseProduits(data, artisteFallback, deptFiltre, null);
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Eventim erreur HTTP", e);
      return ResultatPageEventim.VIDE;
    }
  }

  @SuppressWarnings("unchecked")
  private ResultatPageEventim parserReponseProduits(
      Map<String, Object> data, String artisteFallback, String deptFiltre, String artisteSourceId) {
    if (data == null) return ResultatPageEventim.VIDE;
    var produits = (List<Map<String, Object>>) data.get(CLE_PRODUCTS);
    if (produits == null || produits.isEmpty()) return ResultatPageEventim.VIDE;

    List<ConcertBrut> concerts = new ArrayList<>();
    for (var p : produits) {
      ConcertBrut c = mapProduit(p, artisteFallback, artisteSourceId);
      if (c != null && (deptFiltre == null || deptFiltre.equals(c.dept()))) concerts.add(c);
    }
    int total =
        data.get(CLE_TOTAL_RESULTS) != null ? ((Number) data.get(CLE_TOTAL_RESULTS)).intValue() : 0;
    return new ResultatPageEventim(concerts, total);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extraireLiveEntertainment(Map<String, Object> produit) {
    var typeAttributes = (Map<String, Object>) produit.get("typeAttributes");
    return typeAttributes != null
        ? (Map<String, Object>) typeAttributes.get("liveEntertainment")
        : null;
  }

  @SuppressWarnings("unchecked")
  private static String extraireUrlProduit(Map<String, Object> produit) {
    String lienDirect = (String) produit.get("link");
    if (lienDirect != null && lienDirect.startsWith("http")) return lienDirect;
    var urlObj = (Map<String, Object>) produit.get("url");
    if (urlObj != null && urlObj.get("domain") != null && urlObj.get("path") != null) {
      return urlObj.get("domain") + (String) urlObj.get("path");
    }
    return null;
  }

  private static ConcertBrut construireConcertEventim(
      Map<String, Object> produit,
      Map<String, Object> liveEnt,
      Map<String, Object> location,
      String artiste,
      String date,
      String heure,
      String artisteSourceId) {
    String salle =
        location != null && location.get("name") != null
            ? Normalisation.normaliserCasse((String) location.get("name"))
            : "Salle inconnue";
    String ville = location != null ? (String) location.get("city") : null;
    String dept =
        Normalisation.extraireDeptFrancais(
            location != null ? (String) location.get("postalCode") : null);
    ville = corrigerVilleEventim(ville, dept, salle);

    String url = extraireUrlProduit(produit);
    // inStock=false couvre à la fois les concerts complets et les préventes : impossible à
    // distinguer via l'API Eventim. On ne stocke pas de statut pour éviter les faux positifs.
    String statut = null;
    // Eventim n'expose pas de venueId stable : on génère un identifiant interne basé sur
    // le nom normalisé pour alimenter salle_sources et vider la liste admin "Sans source".
    // Écrasable par un vrai venueId si Eventim en expose un à l'avenir (ON CONFLICT DO NOTHING).
    String salleNorm = Normalisation.normaliserTexte(salle);
    boolean salleInconnue = "Salle inconnue".equals(salle);
    String salleSourceId =
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

  @SuppressWarnings("unchecked")
  private String rechercherAttractionId(String terme) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/v1/attractions")
            .queryParam(PARAM_WEB_ID, WEB_ID)
            .queryParam(PARAM_LANGUE, "fr")
            .queryParam("search_term", terme)
            .queryParam("top", 5)
            .build()
            .toUriString();

    compteurBilan.enregistrerAppel();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return null;
      var attractions = (List<Map<String, Object>>) data.get("attractions");
      if (attractions == null || attractions.isEmpty()) return null;

      String termeNorm = Normalisation.normaliserTexte(terme);
      Map<String, Object> exact = null;
      Map<String, Object> parContainment = null;
      for (var a : attractions) {
        String nomNorm = Normalisation.normaliserTexte((String) a.getOrDefault("name", ""));
        if (nomNorm.equals(termeNorm)) {
          exact = a;
          break;
        }
        // Accepte "terme" contenu dans le nom Eventim (ex. "-M-" dans "-M- Matthieu Chedid").
        // Seuil sur la longueur brute pour éviter les faux positifs sur un seul caractère.
        if (parContainment == null
            && terme.length() >= 2
            && !termeNorm.isEmpty()
            && nomNorm.contains(termeNorm)) {
          parContainment = a;
        }
      }
      if (exact == null && parContainment != null) {
        String nomRetourne = (String) parContainment.getOrDefault("name", "");
        log.info(
            "Eventim: correspondance par containment pour terme={} — résultat={} accepté",
            terme,
            nomRetourne);
        containmentsAcceptes.add("terme=" + terme + " → resultat=" + nomRetourne);
        exact = parContainment;
      }
      if (exact == null) {
        String nomRetourne = (String) attractions.get(0).getOrDefault("name", "");
        log.warn(
            "Eventim: aucune correspondance exacte pour terme={} — premier résultat={} ignoré",
            terme,
            nomRetourne);
        return null;
      }
      Object id = exact.get("attractionId");
      if (id == null) id = exact.get("id");
      return id != null ? String.valueOf(id) : null;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Eventim attractions erreur terme={}", terme, e);
      return null;
    }
  }

  private List<ConcertBrut> fetchProduitsParAttraction(
      String attractionId, String artisteFallback) {
    LocalDate dateDebut = LocalDate.now();
    LocalDate dateFin = dateDebut.plusMonths(24);
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    List<ConcertBrut> concerts = new ArrayList<>();
    boolean continuer = true;
    String artisteSourceId = "eventim:" + attractionId;

    for (int page = 1; page <= MAX_PAGES && continuer; page++) {
      if (page > 1) continuer = attendreEntrePagesEventim();
      if (continuer) {
        String uri =
            UriComponentsBuilder.fromUriString(BASE_URL + "/v1/products")
                .queryParam(PARAM_WEB_ID, WEB_ID)
                .queryParam(PARAM_LANGUE, "fr")
                .queryParam("attraction_ids", attractionId)
                .queryParam("date_from", fmt.format(dateDebut))
                .queryParam("date_to", fmt.format(dateFin))
                .queryParam("sort", "DateAsc")
                .queryParam("top", TAILLE_PAGE)
                .queryParam("page", page)
                .build()
                .toUriString();
        ResultatPageEventim resultat =
            chargerPageProduits(uri, artisteFallback, null, artisteSourceId);
        concerts.addAll(resultat.concerts());
        continuer = !resultat.estVide() && concerts.size() < resultat.total();
      }
    }

    return concerts;
  }

  private List<ConcertBrut> fetchProduitsParRegion(String dept, List<String> classifications) {
    LocalDate dateDebut = LocalDate.now();
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    List<ConcertBrut> concerts = new ArrayList<>();
    boolean continuer = true;

    for (int page = 1; page <= MAX_PAGES && continuer; page++) {
      if (page > 1) continuer = attendreEntrePagesEventim();
      if (continuer) {
        var uriBuilder =
            UriComponentsBuilder.fromUriString(BASE_URL + "/v1/products")
                .queryParam(PARAM_WEB_ID, WEB_ID)
                .queryParam(PARAM_LANGUE, "fr")
                .queryParam("country", "FR")
                .queryParam("query", dept)
                .queryParam("date_from", fmt.format(dateDebut))
                .queryParam("sort", "DateAsc")
                .queryParam("in_stock", "true")
                .queryParam("top", TAILLE_PAGE)
                .queryParam("page", page);
        for (String c : classifications) uriBuilder = uriBuilder.queryParam("categories", c);
        java.net.URI uri = uriBuilder.build().encode().toUri();
        ResultatPageEventim resultat = chargerPageProduits(uri, null, dept);
        concerts.addAll(resultat.concerts());
        continuer = !resultat.estVide() && concerts.size() < resultat.total();
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
  @Override
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
  /// <p>Le paramètre {@code classifs} est ignoré : Eventim utilise ses propres catégories
  /// ({@link #CATEGORIES_REGION}) qui ne correspondent pas aux classifications Ticketmaster.
  ///
  /// @param depts   liste de codes département (ex: ["75", "92"])
  /// @param classifs ignoré — toujours {@link #CATEGORIES_REGION}
  /// @return liste des concerts trouvés pour tous les départements
  @Override
  public List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    List<String> categories = CATEGORIES_REGION;
    List<ConcertBrut> tous = new ArrayList<>();

    for (String dept : depts) {
      List<ConcertBrut> concerts = fetchProduitsParRegion(dept, categories);
      tous.addAll(concerts);
      if (!attendreEntrePagesEventim()) break;
    }

    return tous;
  }
}
