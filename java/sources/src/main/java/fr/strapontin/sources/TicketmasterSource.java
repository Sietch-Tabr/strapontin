/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/// Client HTTP pour l'API Ticketmaster Discovery v2.
///
/// Effectue les appels REST vers TM : par artiste, par salle (venueId), par lot d'attractionIds
/// et par région (géolocalisation département). Respecte le rate limit de 5 req/s via des pauses
/// entre les pages. Le quota journalier est extrait des headers `Rate-Limit-*` de chaque
/// réponse.
///
/// @since 2.0.0
/// @author Kwaite
public class TicketmasterSource implements SourceAvecFetchLot {

  private static final Logger log = LoggerFactory.getLogger(TicketmasterSource.class);

  private static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";

  /// Classifications Ticketmaster filtrées lors des fetchs (musique, opéra, comédie musicale).
  /// Valeur canonique partagée — référencée par le worker plutôt que redéfinie.
  public static final List<String> CLASSIFICATIONS = List.of("music", "opera", "musical");
  private static final int TAILLE_PAGE_REGION = 200;
  // TM refuse offset > 1000 : avec size=200, page 6 → offset 1000 (ok), page 7 → 1200 (HTTP 400)
  private static final int NB_PAGES_MAX = 6;
  // 250 ms entre appels = 4 req/s max — protège contre le spike arrest TM (5 req/s) même sous
  // concurrence
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 250;
  private static final String CHEMIN_EVENTS = "/events.json";
  private static final String TRI_DATE_ASC = "date,asc";
  private static final String CLE_EMBEDDED = "_embedded";

  private static final Set<String> ARTICLES_FR =
      Set.of(
          "de", "du", "des", "d'", "le", "la", "les", "l'", "en", "au", "aux", "et", "à", "sur",
          "sous");

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

  // Correspondance département → centre géographique (Nominatim/OSM, stable)
  static final Map<String, CentreDept> CENTRES_DEPT =
      Map.ofEntries(
          Map.entry("01", new CentreDept(46.0654, 5.4489, 76)),
          Map.entry("02", new CentreDept(49.4533, 3.6069, 83)),
          Map.entry("03", new CentreDept(46.3674, 3.1412, 83)),
          Map.entry("04", new CentreDept(44.1641, 6.2328, 81)),
          Map.entry("05", new CentreDept(44.6565, 6.2478, 84)),
          Map.entry("06", new CentreDept(43.9213, 7.1771, 66)),
          Map.entry("07", new CentreDept(44.8153, 4.3737, 74)),
          Map.entry("08", new CentreDept(49.6981, 4.7093, 72)),
          Map.entry("09", new CentreDept(42.9447, 1.5013, 69)),
          Map.entry("10", new CentreDept(48.3201, 4.1244, 71)),
          Map.entry("11", new CentreDept(43.0543, 2.4646, 78)),
          Map.entry("12", new CentreDept(44.316, 2.6459, 95)),
          Map.entry("13", new CentreDept(43.541, 5.0218, 77)),
          Map.entry("14", new CentreDept(49.0908, -0.3566, 70)),
          Map.entry("15", new CentreDept(45.0497, 2.7171, 71)),
          Map.entry("16", new CentreDept(45.6667, 0.2419, 76)),
          Map.entry("17", new CentreDept(45.7302, -0.7783, 94)),
          Map.entry("18", new CentreDept(47.0248, 2.4266, 84)),
          Map.entry("19", new CentreDept(45.3429, 1.8778, 70)),
          Map.entry("20", new CentreDept(42.1804, 9.0474, 104)),
          Map.entry("21", new CentreDept(47.4655, 4.7921, 84)),
          Map.entry("22", new CentreDept(48.5728, -2.7875, 89)),
          Map.entry("23", new CentreDept(46.0594, 1.992, 65)),
          Map.entry("24", new CentreDept(45.1429, 0.7031, 87)),
          Map.entry("25", new CentreDept(47.067, 6.3806, 77)),
          Map.entry("26", new CentreDept(44.7296, 5.2386, 83)),
          Map.entry("27", new CentreDept(49.0756, 1.0495, 72)),
          Map.entry("28", new CentreDept(48.4475, 1.3751, 72)),
          Map.entry("29", new CentreDept(48.2292, -4.2653, 88)),
          Map.entry("30", new CentreDept(43.9599, 4.0537, 85)),
          Map.entry("31", new CentreDept(43.3054, 1.2451, 95)),
          Map.entry("32", new CentreDept(43.6954, 0.4604, 74)),
          Map.entry("33", new CentreDept(44.8979, -0.4752, 100)),
          Map.entry("34", new CentreDept(43.5915, 3.3671, 79)),
          Map.entry("35", new CentreDept(48.1767, -1.6523, 77)),
          Map.entry("36", new CentreDept(46.8122, 1.5358, 73)),
          Map.entry("37", new CentreDept(47.2232, 0.7094, 74)),
          Map.entry("38", new CentreDept(45.2898, 5.5503, 92)),
          Map.entry("39", new CentreDept(46.7834, 5.7297, 69)),
          Map.entry("40", new CentreDept(44.0099, -0.6935, 89)),
          Map.entry("41", new CentreDept(47.6598, 1.4143, 82)),
          Map.entry("42", new CentreDept(45.7538, 4.2246, 72)),
          Map.entry("43", new CentreDept(45.0857, 3.7863, 67)),
          Map.entry("44", new CentreDept(47.3481, -1.7875, 84)),
          Map.entry("45", new CentreDept(47.914, 2.3204, 77)),
          Map.entry("46", new CentreDept(44.625, 1.5963, 68)),
          Map.entry("47", new CentreDept(44.3692, 0.4689, 66)),
          Map.entry("48", new CentreDept(44.5426, 3.4903, 63)),
          Map.entry("49", new CentreDept(47.3893, -0.5599, 76)),
          Map.entry("50", new CentreDept(49.0919, -1.3449, 84)),
          Map.entry("51", new CentreDept(48.9613, 4.218, 78)),
          Map.entry("52", new CentreDept(48.1329, 5.2592, 78)),
          Map.entry("53", new CentreDept(48.1511, -0.6439, 64)),
          Map.entry("54", new CentreDept(48.9561, 6.2746, 92)),
          Map.entry("55", new CentreDept(49.013, 5.3713, 76)),
          Map.entry("56", new CentreDept(47.7443, -2.8853, 82)),
          Map.entry("57", new CentreDept(49.0207, 6.7653, 84)),
          Map.entry("58", new CentreDept(47.1197, 3.5381, 74)),
          Map.entry("59", new CentreDept(50.529, 3.1551, 99)),
          Map.entry("60", new CentreDept(49.4121, 2.4273, 67)),
          Map.entry("61", new CentreDept(48.576, 0.058, 81)),
          Map.entry("62", new CentreDept(50.5143, 2.3718, 80)),
          Map.entry("63", new CentreDept(45.7717, 3.1868, 82)),
          Map.entry("64", new CentreDept(43.1872, -0.8804, 87)),
          Map.entry("65", new CentreDept(43.1438, 0.1595, 66)),
          Map.entry("66", new CentreDept(42.6257, 2.4506, 68)),
          Map.entry("67", new CentreDept(48.5991, 7.5866, 72)),
          Map.entry("68", new CentreDept(47.8658, 7.2317, 58)),
          Map.entry("69", new CentreDept(45.8803, 4.702, 60)),
          Map.entry("70", new CentreDept(47.6384, 6.0962, 70)),
          Map.entry("71", new CentreDept(46.6557, 4.5439, 90)),
          Map.entry("72", new CentreDept(48.0267, 0.2343, 72)),
          Map.entry("73", new CentreDept(45.4949, 6.4039, 79)),
          Map.entry("74", new CentreDept(46.069, 6.4252, 65)),
          Map.entry("75", new CentreDept(48.8589, 2.3469, 11)),
          Map.entry("76", new CentreDept(49.6614, 0.9283, 77)),
          Map.entry("77", new CentreDept(48.619, 2.9758, 70)),
          Map.entry("78", new CentreDept(48.762, 1.8377, 46)),
          Map.entry("79", new CentreDept(46.539, -0.3416, 77)),
          Map.entry("80", new CentreDept(49.9691, 2.2913, 79)),
          Map.entry("81", new CentreDept(43.7922, 2.2363, 73)),
          Map.entry("82", new CentreDept(44.0807, 1.3693, 62)),
          Map.entry("83", new CentreDept(43.3954, 6.2946, 69)),
          Map.entry("84", new CentreDept(44.0451, 5.2035, 62)),
          Map.entry("85", new CentreDept(46.6758, -1.4694, 85)),
          Map.entry("86", new CentreDept(46.6121, 0.5541, 81)),
          Map.entry("87", new CentreDept(45.919, 1.2699, 73)),
          Map.entry("88", new CentreDept(48.1637, 6.296, 78)),
          Map.entry("89", new CentreDept(47.8552, 3.5943, 83)),
          Map.entry("90", new CentreDept(47.6292, 6.9499, 27)),
          Map.entry("91", new CentreDept(48.5304, 2.2499, 37)),
          Map.entry("92", new CentreDept(48.8402, 2.2413, 15)),
          Map.entry("93", new CentreDept(48.9098, 2.4458, 17)),
          Map.entry("94", new CentreDept(48.7745, 2.4622, 15)),
          Map.entry("95", new CentreDept(49.0751, 2.1014, 41)));

  private final String apiKey;
  private final RestClient restClient;
  private final AtomicReference<QuotaTM> dernierQuota = new AtomicReference<>();
  // Compteurs cumulés depuis le dernier prélèvement (remontés dans logs_admin par le worker).
  private final AtomicInteger concertsManquesCumules = new AtomicInteger();
  private final AtomicInteger erreursHttpCumulees = new AtomicInteger();
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// Crée une instance avec la clé API fournie et un RestClient par défaut.
  ///
  /// @param apiKey clé API Ticketmaster Discovery v2
  public TicketmasterSource(String apiKey) {
    this(apiKey, RestClient.create());
  }

  /// Crée une instance avec la clé API et un RestClient personnalisé (injection de test).
  ///
  /// @param apiKey clé API Ticketmaster Discovery v2
  /// @param restClient client HTTP Spring à utiliser
  public TicketmasterSource(String apiKey, RestClient restClient) {
    this.apiKey = apiKey;
    this.restClient = restClient;
  }

  /// Retourne le dernier quota TM extrait des headers HTTP, ou `null` si aucun appel n'a été
  /// fait.
  ///
  /// @return quota courant ou null
  public QuotaTM obtenirQuota() {
    return dernierQuota.get();
  }

  /// Bilan agrégé des fetchs Ticketmaster depuis le dernier prélèvement.
  ///
  /// @param concertsManques concerts non récupérés (pagination plafonnée à {@code NB_PAGES_MAX})
  /// @param erreursHttp      nombre de réponses HTTP Ticketmaster en erreur
  public record BilanFetch(int concertsManques, int erreursHttp) {}

  /// Retourne le bilan cumulé des fetchs et remet les compteurs à zéro.
  ///
  /// @return le bilan depuis le dernier appel
  public BilanFetch preleverBilan() {
    return new BilanFetch(concertsManquesCumules.getAndSet(0), erreursHttpCumulees.getAndSet(0));
  }

  /// Indique si un horodatage ISO 8601 (avec offset ou {@code Z}) est dans le futur.
  ///
  /// @param iso horodatage à analyser
  /// @return {@code true} si postérieur à maintenant, {@code false} si passé ou illisible
  private static boolean estDansLeFutur(String iso) {
    try {
      return java.time.OffsetDateTime.parse(iso).toInstant().isAfter(java.time.Instant.now());
    } catch (Exception e) {
      return false;
    }
  }

  /// Extrait le code département depuis un code postal français.
  ///
  /// Les DOM-TOM commençant par 97 retournent les 3 premiers chiffres. La Corse (2A/2B) est
  /// déjà normalisée en "20" à la saisie.
  ///
  /// @param codePostal code postal (peut être null)
  /// @return code département à 2 ou 3 chiffres, ou null si absent/invalide
  public static String extraireDept(String codePostal) {
    if (codePostal == null || codePostal.isBlank()) return null;
    if (codePostal.startsWith("97")) return codePostal.substring(0, 3);
    return codePostal.substring(0, 2);
  }

  /// Extrait la date et l'heure d'un événement TM depuis les champs localDate / localTime.
  ///
  /// L'heure "00:00" est considérée comme absente — TM utilise minuit par défaut pour les
  /// événements sans heure précise, symétrique au comportement d'Eventim.
  ///
  /// @param dateStr  valeur de localDate (format ISO 8601, au moins 10 chars)
  /// @param heureStr valeur de localTime (peut être null ou "00:00:00" si non définie)
  /// @return tableau à deux éléments : [date, heure] où heure peut être null
  public static String[] parseDateTM(String dateStr, String heureStr) {
    String date = dateStr.substring(0, 10);
    if (heureStr == null) return new String[] {date, null};
    String heure = heureStr.substring(0, 5);
    return new String[] {date, "00:00".equals(heure) ? null : heure};
  }

  /// Convertit un nom tout-majuscule en title case français.
  ///
  /// Les articles courants restent en minuscules sauf en début de chaîne. Si la chaîne
  /// n'est pas entièrement en majuscules, elle est retournée sans modification.
  ///
  /// @param nom nom à normaliser
  /// @return nom en title case ou nom inchangé
  private static final Pattern SEP_NORMALISER_CASSE = Pattern.compile("\\s+|-");

  public static String normaliserCasse(String nom) {
    if (!nom.equals(nom.toUpperCase())) return nom;
    Matcher m = SEP_NORMALISER_CASSE.matcher(nom);
    StringBuilder sb = new StringBuilder();
    boolean debutPhrase = true;
    int dernier = 0;
    while (m.find()) {
      String mot = nom.substring(dernier, m.start());
      if (!mot.isEmpty()) {
        String bas = mot.toLowerCase();
        if (!debutPhrase && ARTICLES_FR.contains(bas)) {
          sb.append(bas);
        } else {
          sb.append(capitaliserAvecApostrophe(bas));
        }
      }
      String sep = m.group();
      sb.append(sep);
      debutPhrase = sep.equals("-");
      dernier = m.end();
    }
    String fin = nom.substring(dernier);
    if (!fin.isEmpty()) {
      String bas = fin.toLowerCase();
      if (!debutPhrase && ARTICLES_FR.contains(bas)) {
        sb.append(bas);
      } else {
        sb.append(capitaliserAvecApostrophe(bas));
      }
    }
    return sb.toString();
  }

  private static String capitaliserAvecApostrophe(String mot) {
    if (mot.isEmpty()) return mot;
    StringBuilder sb = new StringBuilder();
    boolean majSuivante = true;
    for (int i = 0; i < mot.length(); i++) {
      char c = mot.charAt(i);
      if (c == '\'' || c == '’') {
        sb.append(c);
        majSuivante = true;
      } else if (majSuivante) {
        sb.append(Character.toUpperCase(c));
        majSuivante = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String normaliserClassification(String nom) {
    if (nom == null) return null;
    return CLASSIFICATION_MAP.getOrDefault(nom, nom);
  }

  /// Mappe un événement TM brut (JSON désérialisé) vers un [ConcertBrut].
  ///
  /// @param evt             événement TM sous forme de Map imbriquée
  /// @param artisteFallback nom de l'artiste cherché (pour fetch:artiste uniquement), peut être
  /// null
  /// @return concert extrait, ou null si l'événement est invalide ou hors périmètre
  public static ConcertBrut mapEvenement(Map<String, Object> evt, String artisteFallback) {
    try {
      return parseEvenementTM(evt, artisteFallback);
    } catch (Exception e) {
      log.warn("erreur parsing événement TM", e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static String extraireArtisteTM(
      Map<String, Object> evt, Map<String, Object> embedded, String artisteFallback) {
    List<Map<String, Object>> attractions =
        embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
    String nomAttraction =
        attractions != null && !attractions.isEmpty()
            ? (String) attractions.get(0).get("name")
            : null;
    if (nomAttraction != null) return normaliserCasse(nomAttraction);
    if (artisteFallback == null) return null;
    String nomEvenement = (String) evt.get("name");
    if (nomEvenement != null
        && nomEvenement.toLowerCase().contains(artisteFallback.toLowerCase())) {
      return artisteFallback;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static String extraireAttractionIdTM(Map<String, Object> embedded) {
    List<Map<String, Object>> attractions =
        embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
    if (attractions == null || attractions.isEmpty()) return null;
    Object id = attractions.get(0).get("id");
    return id != null ? "ticketmaster:" + id : null;
  }

  @SuppressWarnings("unchecked")
  private static Double[] extrairePrixTM(Map<String, Object> evt) {
    var priceRanges = (List<Map<String, Object>>) evt.get("priceRanges");
    if (priceRanges == null || priceRanges.isEmpty()) return new Double[] {null, null};
    Object min = priceRanges.get(0).get("min");
    Object max = priceRanges.get(0).get("max");
    return new Double[] {
      min != null ? ((Number) min).doubleValue() : null,
      max != null ? ((Number) max).doubleValue() : null
    };
  }

  @SuppressWarnings("unchecked")
  private static String[] extraireClassificationTM(List<Map<String, Object>> classificationsData) {
    if (classificationsData == null || classificationsData.isEmpty()) {
      return new String[] {null, null};
    }
    var segment = (Map<String, String>) classificationsData.get(0).get("segment");
    String segmentNom =
        segment != null && segment.get("name") != null ? segment.get("name").toLowerCase() : null;
    var genreMap = (Map<String, String>) classificationsData.get(0).get("genre");
    String genre = genreMap != null ? genreMap.get("name") : null;
    return new String[] {segmentNom, genre};
  }

  @SuppressWarnings("unchecked")
  private static ConcertBrut parseEvenementTM(Map<String, Object> evt, String artisteFallback) {
    var dates = (Map<String, Object>) evt.get("dates");
    var start = dates != null ? (Map<String, Object>) dates.get("start") : null;
    String dateStr = start != null ? (String) start.get("localDate") : null;
    if (dateStr == null) return null;

    String[] datHeure = parseDateTM(dateStr, (String) start.get("localTime"));

    var embedded = (Map<String, Object>) evt.get(CLE_EMBEDDED);
    String artiste = extraireArtisteTM(evt, embedded, artisteFallback);
    if (artiste == null) return null;

    List<Map<String, Object>> venues =
        embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
    Map<String, Object> venue = venues != null && !venues.isEmpty() ? venues.get(0) : null;
    String salle =
        normaliserCasse(
            venue != null && venue.get("name") != null
                ? (String) venue.get("name")
                : "Salle inconnue");
    Map<String, String> cityMap = venue != null ? (Map<String, String>) venue.get("city") : null;
    String ville = cityMap != null ? cityMap.get("name") : null;
    String dept = extraireDept(venue != null ? (String) venue.get("postalCode") : null);

    Object venueIdObj = venue != null ? venue.get("id") : null;
    String salleSourceId = venueIdObj != null ? "ticketmaster:" + venueIdObj : null;
    String artisteSourceId = extraireAttractionIdTM(embedded);

    Double[] prix = extrairePrixTM(evt);
    String url = (String) evt.get("url");
    Map<String, String> statusMap = (Map<String, String>) dates.get("status");
    String statut = statusMap != null ? statusMap.get("code") : null;

    List<Map<String, Object>> classificationsData =
        (List<Map<String, Object>>) evt.get("classifications");
    String[] segmentGenre = extraireClassificationTM(classificationsData);

    var sales = (Map<String, Object>) evt.get("sales");
    var presales = sales != null ? (List<Map<String, Object>>) sales.get("presales") : null;
    var presale = presales != null && !presales.isEmpty() ? presales.get(0) : null;
    String presaleDebut = presale != null ? (String) presale.get("startDateTime") : null;
    String presaleNom = presale != null ? (String) presale.get("name") : null;

    if ("offsale".equals(statut)) {
      var ventePublique = sales != null ? (Map<String, Object>) sales.get("public") : null;
      String ouverturePublique =
          ventePublique != null ? (String) ventePublique.get("startDateTime") : null;
      if (presaleDebut != null) {
        statut = "presale";
      } else if (ouverturePublique != null && estDansLeFutur(ouverturePublique)) {
        // Vente générale pas encore ouverte : c'est une prévente à venir, pas un concert complet.
        statut = "presale";
        presaleDebut = ouverturePublique;
      }
    }

    return new ConcertBrut(
        artiste,
        datHeure[0],
        datHeure[1],
        salle,
        ville,
        dept,
        prix[0],
        prix[1],
        url,
        "ticketmaster",
        normaliserClassification(segmentGenre[0]),
        segmentGenre[1],
        statut,
        presaleDebut,
        presaleNom,
        salleSourceId,
        artisteSourceId);
  }

  private record ResultatPage(
      List<ConcertBrut> concerts, int total, int totalPages, QuotaTM quota) {}

  /// Garantit un écart minimal entre deux requêtes HTTP vers TM, quel que soit le nombre de jobs
  /// Quartz en parallèle. Le verrou est sur l'instance (singleton Spring).
  private synchronized void attendreRateLimitGlobal() {
    long maintenant = System.currentTimeMillis();
    long prochainAppel = dernierAppelMs.get() + DELAI_MIN_ENTRE_APPELS_MS;
    if (maintenant < prochainAppel) {
      try {
        Thread.sleep(prochainAppel - maintenant);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }

  @SuppressWarnings("unchecked")
  private ResultatPage fetchPage(
      UriComponentsBuilder uriBase, int numPage, String artisteFallback) {
    attendreRateLimitGlobal();
    String uri =
        uriBase
            .cloneBuilder()
            .queryParam("apikey", apiKey)
            .queryParam("locale", "fr,en,*")
            .queryParam("countryCode", "FR")
            .queryParam("includeTBA", "no")
            .queryParam("includeTBD", "no")
            .queryParam("page", numPage)
            .build()
            .toUriString();

    try {
      var response = restClient.get().uri(uri).retrieve().toEntity(Map.class);

      QuotaTM quota = null;
      var headers = response.getHeaders();
      if (headers.get("Rate-Limit-Available") != null) {
        quota =
            new QuotaTM(
                Integer.parseInt(headers.getFirst("Rate-Limit-Available")),
                Integer.parseInt(headers.getOrEmpty("Rate-Limit").stream().findFirst().orElse("0")),
                Long.parseLong(
                    headers.getOrEmpty("Rate-Limit-Reset").stream().findFirst().orElse("0")),
                Instant.now().toString());
      }

      var data = response.getBody();
      if (data == null) return new ResultatPage(List.of(), 0, NB_PAGES_MAX, quota);

      var page = (Map<String, Object>) data.get("page");
      int total = page != null ? ((Number) page.get("totalElements")).intValue() : 0;
      int totalPages = page != null ? ((Number) page.get("totalPages")).intValue() : NB_PAGES_MAX;

      var embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      List<Map<String, Object>> evts =
          embedded != null ? (List<Map<String, Object>>) embedded.get("events") : null;
      if (evts == null) return new ResultatPage(List.of(), total, totalPages, quota);

      List<ConcertBrut> concerts = new ArrayList<>();
      for (var evt : evts) {
        ConcertBrut c = mapEvenement(evt, artisteFallback);
        if (c != null) concerts.add(c);
      }
      return new ResultatPage(concerts, total, totalPages, quota);

    } catch (Exception e) {
      String uriSafe = uri.replaceAll("apikey=[^&]+", "apikey=***");
      log.warn("erreur HTTP Ticketmaster url={}", uriSafe, e);
      erreursHttpCumulees.incrementAndGet();
      return new ResultatPage(List.of(), 0, NB_PAGES_MAX, null);
    }
  }

  private record FenetreTemps(String debut, String fin) {}

  private static FenetreTemps obtenirFenetreTemps() {
    Instant maintenant = Instant.now();
    Instant dans6Mois = maintenant.plus(6L * 30, ChronoUnit.DAYS);
    DateTimeFormatter fmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    return new FenetreTemps(fmt.format(maintenant), fmt.format(dans6Mois));
  }

  private List<ConcertBrut> fetchToutesPages(UriComponentsBuilder uriBase, String artisteFallback) {
    List<ConcertBrut> tousLesEvents = new ArrayList<>();
    int borneMax = NB_PAGES_MAX;
    int totalElements = 0;
    boolean continuer = true;

    for (int numPage = 0; numPage < borneMax && continuer; numPage++) {
      ResultatPage resultat = fetchPage(uriBase, numPage, artisteFallback);
      if (resultat.quota() != null) dernierQuota.set(resultat.quota());
      if (numPage == 0) {
        totalElements = resultat.total();
        borneMax = Math.min(resultat.totalPages(), NB_PAGES_MAX);
      }
      tousLesEvents.addAll(resultat.concerts());

      boolean fini =
          resultat.concerts().isEmpty()
              || (totalElements > 0 && tousLesEvents.size() >= totalElements);
      continuer = !fini;
    }

    int nbPagesTotal = (int) Math.ceil((double) totalElements / TAILLE_PAGE_REGION);
    if (nbPagesTotal > NB_PAGES_MAX) {
      concertsManquesCumules.addAndGet(Math.max(0, totalElements - tousLesEvents.size()));
      log.warn(
          "NB_PAGES_MAX atteint — concerts manqués total={} recuperes={} pagesFetched={}"
              + " pagesTotal={}",
          totalElements,
          tousLesEvents.size(),
          NB_PAGES_MAX,
          nbPagesTotal);
    }

    return tousLesEvents;
  }

  @Override
  public String nom() {
    return "ticketmaster";
  }

  @Override
  public boolean accepteIdentifiantSalle(String identifiant) {
    return identifiant != null && identifiant.startsWith("tm:");
  }

  /// Récupère les concerts d'un artiste par mot-clé (une seule page TM).
  ///
  /// @param nom nom de l'artiste recherché
  /// @return liste des concerts trouvés
  @Override
  public List<ConcertBrut> fetchArtiste(String nom) {
    FenetreTemps fenetre = obtenirFenetreTemps();
    var uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("keyword", nom)
            .queryParam("classificationName", CLASSIFICATIONS.toArray())
            .queryParam("size", TAILLE_PAGE_REGION)
            .queryParam("sort", TRI_DATE_ASC)
            .queryParam("startDateTime", fenetre.debut());

    ResultatPage resultat = fetchPage(uriBase, 0, nom);
    if (resultat.quota() != null) dernierQuota.set(resultat.quota());
    log.info("TM fetchArtiste artiste={} total={}", nom, resultat.total());
    return resultat.concerts();
  }

  /// Récupère les concerts d'une salle TM par identifiant préfixé ou brut.
  ///
  /// <p>Accepte un identifiant préfixé ({@code "tm:rZFSnyZdFvA"}) ou un venueId brut
  /// ({@code "rZFSnyZdFvA"}).
  ///
  /// @param identifiant identifiant de la salle (préfixé {@code "tm:"} ou brut)
  /// @return liste des concerts à venir dans cette salle
  @Override
  public List<ConcertBrut> fetchSalle(String identifiant) {
    String venueId = identifiant.startsWith("tm:") ? identifiant.substring(3) : identifiant;
    FenetreTemps fenetre = obtenirFenetreTemps();
    var uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("venueId", venueId)
            .queryParam("size", TAILLE_PAGE_REGION)
            .queryParam("sort", TRI_DATE_ASC)
            .queryParam("startDateTime", fenetre.debut())
            .queryParam("endDateTime", fenetre.fin());

    List<ConcertBrut> concerts = fetchToutesPages(uriBase, null);
    log.info("TM fetchSalle venueId={} total={}", venueId, concerts.size());
    return concerts;
  }

  /// Recherche l'identifiant TM (attractionId) pour un artiste par son nom.
  ///
  /// <p>Parcourt jusqu'à 10 résultats pour trouver la première correspondance exacte normalisée.
  /// TM peut trier par popularité et retourner un artiste similaire avant l'artiste recherché
  /// (ex: "Shy" avant "Shy'm" — #654).
  ///
  /// @param nom nom de l'artiste
  /// @return attractionId TM, ou null si aucun résultat exact dans les 10 premiers
  @SuppressWarnings("unchecked")
  public String rechercherAttractionId(String nom) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/attractions.json")
            .queryParam("keyword", nom)
            .queryParam("locale", "*")
            .queryParam("size", 10)
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
    attendreRateLimitGlobal();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return null;
      var embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      var attractions =
          embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
      if (attractions == null || attractions.isEmpty()) return null;
      String nomNorm = Normalisation.normaliserTexte(nom);
      for (var attraction : attractions) {
        String nomAttraction = (String) attraction.getOrDefault("name", "");
        if (Normalisation.normaliserTexte(nomAttraction).equals(nomNorm)) {
          return (String) attraction.get("id");
        }
      }
      log.warn("rechercherAttractionId TM — pas de correspondance exacte nom={}", nom);
      return null;
    } catch (Exception e) {
      log.warn("rechercherAttractionId TM échoué nom='{}' : {}", nom, e.getMessage());
      return null;
    }
  }

  /// Récupère les concerts d'un lot d'artistes TM par leurs attractionIds.
  ///
  /// @param attractionIds liste d'identifiants TM d'attractions
  /// @return liste des concerts trouvés pour tous les artistes du lot
  @Override
  public List<ConcertBrut> fetchArtistesLot(List<String> attractionIds) {
    if (attractionIds.isEmpty()) return List.of();
    FenetreTemps fenetre = obtenirFenetreTemps();
    var uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("attractionId", String.join(",", attractionIds))
            .queryParam("size", TAILLE_PAGE_REGION)
            .queryParam("sort", TRI_DATE_ASC)
            .queryParam("startDateTime", fenetre.debut());

    List<ConcertBrut> concerts = fetchToutesPages(uriBase, null);
    if (dernierQuota.get() != null) {
      log.info("TM fetchArtistesLot ids={} total={}", attractionIds.size(), concerts.size());
    }
    return concerts;
  }

  /// Récupère les concerts par région (géolocalisation centre de département).
  ///
  /// @param depts   liste de codes département (ex: ["75", "92"])
  /// @param classifs classifications TM à inclure ; par défaut music, opera, musical
  /// @return liste des concerts trouvés pour tous les départements
  @Override
  public List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    if (classifs == null) classifs = CLASSIFICATIONS;
    List<ConcertBrut> tous = new ArrayList<>();

    for (int i = 0; i < depts.size(); i++) {
      String dept = depts.get(i);
      CentreDept centre = CENTRES_DEPT.get(dept);
      if (centre == null) {
        log.warn("département sans coordonnées connues, ignoré dept={}", dept);
      } else {
        FenetreTemps fenetre = obtenirFenetreTemps();
        var uriBase =
            UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
                .queryParam("latlong", centre.lat() + "," + centre.lng())
                .queryParam("radius", centre.rayon())
                .queryParam("unit", "km")
                .queryParam("classificationName", classifs.toArray())
                .queryParam("size", TAILLE_PAGE_REGION)
                .queryParam("sort", TRI_DATE_ASC)
                .queryParam("startDateTime", fenetre.debut())
                .queryParam("endDateTime", fenetre.fin());

        List<ConcertBrut> concerts = fetchToutesPages(uriBase, null);
        tous.addAll(concerts);
      }
    }

    return tous;
  }
}
