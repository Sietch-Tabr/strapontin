/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/// Client HTTP pour l'API Ticketmaster Discovery v2.
///
/// Effectue les appels REST vers TM : par artiste, par salle (venueId), par lot d'attractionIds et
/// par région (géolocalisation département). Respecte le rate limit de 5 req/s via des pauses entre
/// les pages. Le quota journalier est extrait des headers `Rate-Limit-*` de chaque réponse.
///
/// @since 2.0.0
/// @author Kwaite
public class TicketmasterSource implements SourceAvecFetchLot, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(TicketmasterSource.class);

  private static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";

  /// Classifications Ticketmaster filtrées lors des fetchs (musique, opéra, comédie musicale).
  /// Valeur canonique partagée — référencée par le worker plutôt que redéfinie.
  public static final List<String> CLASSIFICATIONS = List.of("music", "opera", "musical");

  /// Préfixe abrégé attendu par {@link #accepteIdentifiantSalle}/{@link #fetchSalle}, distinct de
  /// {@link #nom()} ({@code "ticketmaster"}, valeur canonique stockée dans {@code
  /// salle_sources.source}). Référencée par les appelants qui construisent l'identifiant préfixé à
  /// partir de cette colonne — sans cette traduction, {@code fetchSalle} n'est jamais invoqué
  /// (mismatch de préfixe silencieux, aucune exception).
  public static final String PREFIXE_IDENTIFIANT = "tm";

  private static final int TAILLE_PAGE_REGION = 200;
  // TM refuse offset > 1000 : avec size=200, page 6 → offset 1000 (ok), page 7 → 1200 (HTTP 400)
  private static final int NB_PAGES_MAX = 6;
  // 250 ms entre appels = 4 req/s max — protège contre le spike arrest TM (5 req/s) même sous
  // concurrence
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 250;
  private static final String CHEMIN_EVENTS = "/events.json";
  private static final String TRI_DATE_ASC = "date,asc";
  private static final String CLE_EMBEDDED = "_embedded";

  private final String apiKey;
  private final RestClient restClient;
  private final AtomicReference<QuotaTM> dernierQuota = new AtomicReference<>();
  // concertsManques reste spécifique à TM (pagination plafonnée), séparé du tronc commun
  // appels/erreurs porté par compteurBilan.
  private final AtomicInteger concertsManquesCumules = new AtomicInteger();
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();
  private final CadenceurAppels cadenceur = new CadenceurAppels(DELAI_MIN_ENTRE_APPELS_MS);
  private final TicketmasterHealthCheck healthCheck;

  /// Crée une instance avec la clé API fournie et un RestClient par défaut (timeout connexion 10s,
  /// lecture 30s).
  ///
  /// @param apiKey clé API Ticketmaster Discovery v2
  public TicketmasterSource(String apiKey) {
    this(
        apiKey,
        RestClient.builder()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build());
  }

  /// Crée une instance avec la clé API et un RestClient personnalisé (injection de test).
  ///
  /// @param apiKey clé API Ticketmaster Discovery v2
  /// @param restClient client HTTP Spring à utiliser
  public TicketmasterSource(String apiKey, RestClient restClient) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    this.restClient = Objects.requireNonNull(restClient, "restClient");
    this.healthCheck = new TicketmasterHealthCheck(this.apiKey, this.restClient, cadenceur);
  }

  /// Retourne le dernier quota TM extrait des headers HTTP, ou `null` si aucun appel n'a été fait.
  ///
  /// @return quota courant ou null
  public QuotaTM obtenirQuota() {
    return dernierQuota.get();
  }

  /// Retourne le bilan agrégé appels/erreurs HTTP depuis le dernier prélèvement et remet les
  /// compteurs à zéro. Le nombre de concerts manqués (pagination plafonnée) reste distinct, voir
  /// {@link #preleverConcertsManques()}.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /// Retourne le nombre cumulé de concerts non récupérés par plafonnement de la pagination
  /// (pagination bornée à {@code NB_PAGES_MAX}) depuis le dernier prélèvement, et remet le compteur
  /// à zéro. Métrique spécifique à TM, distincte du bilan appels/erreurs commun.
  ///
  /// @return le nombre de concerts manqués depuis le dernier prélèvement
  public int preleverConcertsManques() {
    return concertsManquesCumules.getAndSet(0);
  }

  /// Extrait la date et l'heure d'un événement TM depuis les champs localDate / localTime.
  ///
  /// @param dateStr valeur de localDate (format ISO 8601, au moins 10 chars)
  /// @param heureStr valeur de localTime (peut être null ou "00:00:00" si non définie)
  /// @return tableau à deux éléments : [date, heure] où heure peut être null
  public static String[] parseDateTM(String dateStr, String heureStr) {
    return MapeurEvenementTM.parseDateTM(dateStr, heureStr);
  }

  /// Mappe un événement TM brut (JSON désérialisé) vers un [ConcertBrut].
  ///
  /// @param evt événement TM sous forme de Map imbriquée
  /// @param artisteFallback nom de l'artiste cherché (pour fetch:artiste uniquement), peut être
  ///   null
  /// @return concert extrait, ou null si l'événement est invalide ou hors périmètre
  public static ConcertBrut mapEvenement(Map<String, Object> evt, String artisteFallback) {
    return MapeurEvenementTM.mapper(evt, artisteFallback);
  }

  /// @param succes {@code false} si l'appel HTTP a échoué (voir {@link #fetchPage}) — distingue une
  ///   page réellement vide (succès, 0 résultat) d'un appel qui n'a jamais abouti, faute de quoi
  ///   les deux se traduisent identiquement par {@code concerts} vide et {@code total=0}.
  private record ResultatPage(
      List<ConcertBrut> concerts, int total, int totalPages, QuotaTM quota, boolean succes) {}

  /// @param succes {@code false} si au moins une page a échoué (voir {@link ResultatPage#succes}).
  private record ResultatMultiPage(List<ConcertBrut> concerts, boolean succes) {}

  @SuppressWarnings("unchecked")
  private ResultatPage fetchPage(
      UriComponentsBuilder uriBase, int numPage, String artisteFallback) {
    cadenceur.attendre();
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

    compteurBilan.enregistrerAppel();
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
      if (data == null) return new ResultatPage(List.of(), 0, NB_PAGES_MAX, quota, true);

      var page = (Map<String, Object>) data.get("page");
      int total = page != null ? ((Number) page.get("totalElements")).intValue() : 0;
      int totalPages = page != null ? ((Number) page.get("totalPages")).intValue() : NB_PAGES_MAX;

      var embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      List<Map<String, Object>> evts =
          embedded != null ? (List<Map<String, Object>>) embedded.get("events") : null;
      if (evts == null) return new ResultatPage(List.of(), total, totalPages, quota, true);

      List<ConcertBrut> concerts = new ArrayList<>();
      for (var evt : evts) {
        ConcertBrut c = mapEvenement(evt, artisteFallback);
        if (c != null) concerts.add(c);
      }
      return new ResultatPage(concerts, total, totalPages, quota, true);

    } catch (Exception e) {
      String uriSafe = uri.replaceAll("apikey=[^&]+", "apikey=***");
      log.warn("erreur HTTP Ticketmaster url={} : {}", uriSafe, redigerApiKey(e.getMessage()));
      compteurBilan.enregistrerErreur();
      return new ResultatPage(List.of(), 0, NB_PAGES_MAX, null, false);
    }
  }

  /// Retire toute clé API Ticketmaster (paramètre {@code apikey=...}) d'un message avant
  /// journalisation. Sur une panne réseau bas niveau, l'exception Spring ({@code
  /// ResourceAccessException}) embarque l'URI complète — y compris sa query string — dans son
  /// propre message, contournant toute rédaction faite en amont sur l'URI seule.
  ///
  /// @param message message à assainir, peut être {@code null}
  /// @return le message avec toute clé API masquée, ou {@code null} si {@code message} est null
  static String redigerApiKey(String message) {
    return message == null ? null : message.replaceAll("apikey=[^&\\s\"]+", "apikey=***");
  }

  private record FenetreTemps(String debut, String fin) {}

  private static FenetreTemps obtenirFenetreTemps() {
    Instant maintenant = Instant.now();
    Instant dans6Mois = maintenant.plus(6L * 30, ChronoUnit.DAYS);
    DateTimeFormatter fmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    return new FenetreTemps(fmt.format(maintenant), fmt.format(dans6Mois));
  }

  /// Récupère toutes les pages disponibles (bornées à {@link #NB_PAGES_MAX}) pour une requête TM.
  ///
  /// @param uriBase URI de base de la requête, sans pagination
  /// @param artisteFallback nom de l'artiste cherché (fetch:artiste uniquement), peut être null
  /// @param contexte identifiant du centre de recherche (ex. {@code "dept=75"}) inclus dans le log
  ///   de plafonnement de pagination, pour distinguer les départements/salles chroniquement
  ///   affectés
  /// @return les concerts récupérés sur toutes les pages parcourues, et si toutes les pages ont été
  ///   récupérées avec succès (voir {@link ResultatPage#succes})
  private ResultatMultiPage fetchToutesPages(
      UriComponentsBuilder uriBase, String artisteFallback, String contexte) {
    List<ConcertBrut> tousLesEvents = new ArrayList<>();
    int borneMax = NB_PAGES_MAX;
    int totalElements = 0;
    boolean continuer = true;
    boolean succes = true;

    for (int numPage = 0; numPage < borneMax && continuer; numPage++) {
      ResultatPage resultat = fetchPage(uriBase, numPage, artisteFallback);
      if (!resultat.succes()) succes = false;
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
          "NB_PAGES_MAX atteint contexte={} — concerts manqués total={} recuperes={}"
              + " pagesFetched={} pagesTotal={}",
          contexte,
          totalElements,
          tousLesEvents.size(),
          NB_PAGES_MAX,
          nbPagesTotal);
    }

    return new ResultatMultiPage(tousLesEvents, succes);
  }

  @Override
  public String nom() {
    return "ticketmaster";
  }

  @Override
  public boolean accepteIdentifiantSalle(String identifiant) {
    return identifiant != null && identifiant.startsWith(PREFIXE_IDENTIFIANT + ":");
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
    if (resultat.succes()) {
      log.info("TM fetchArtiste artiste={} total={}", nom, resultat.total());
    } else {
      log.warn("TM fetchArtiste artiste={} échoué (voir erreur HTTP ci-dessus)", nom);
    }
    return resultat.concerts();
  }

  /// Récupère les concerts d'une salle TM par identifiant préfixé ou brut.
  ///
  /// <p>Accepte un identifiant préfixé ({@code "tm:rZFSnyZdFvA"}) ou un venueId brut ({@code
  /// "rZFSnyZdFvA"}).
  ///
  /// @param identifiant identifiant de la salle (préfixé {@code "tm:"} ou brut)
  /// @return liste des concerts à venir dans cette salle
  @Override
  public List<ConcertBrut> fetchSalle(String identifiant) {
    String venueId =
        identifiant.startsWith(PREFIXE_IDENTIFIANT + ":")
            ? identifiant.substring(PREFIXE_IDENTIFIANT.length() + 1)
            : identifiant;
    FenetreTemps fenetre = obtenirFenetreTemps();
    var uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("venueId", venueId)
            .queryParam("size", TAILLE_PAGE_REGION)
            .queryParam("sort", TRI_DATE_ASC)
            .queryParam("startDateTime", fenetre.debut())
            .queryParam("endDateTime", fenetre.fin());

    ResultatMultiPage resultat = fetchToutesPages(uriBase, null, "salle=" + venueId);
    if (resultat.succes()) {
      log.info("TM fetchSalle venueId={} total={}", venueId, resultat.concerts().size());
    } else {
      log.warn("TM fetchSalle venueId={} échoué (voir erreur HTTP ci-dessus)", venueId);
    }
    return resultat.concerts();
  }

  /// Recherche l'identifiant TM (attractionId) pour un artiste par son nom.
  ///
  /// <p>Parcourt jusqu'à 10 résultats pour trouver la première correspondance exacte normalisée. TM
  /// peut trier par popularité et retourner un artiste similaire avant l'artiste recherché (ex:
  /// "Shy" avant "Shy'm").
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
    cadenceur.attendre();
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
      log.warn(
          "rechercherAttractionId TM échoué nom='{}' : {}", nom, redigerApiKey(e.getMessage()));
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
    // Valeur exacte envoyée (pas seulement le nombre) — permet de corréler après coup un
    // identifiant attendu (base) avec celui réellement transmis en cas d'écart constaté.
    log.info("TM fetchArtistesLot ids envoyés={}", attractionIds);
    FenetreTemps fenetre = obtenirFenetreTemps();
    var uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("attractionId", String.join(",", attractionIds))
            .queryParam("size", TAILLE_PAGE_REGION)
            .queryParam("sort", TRI_DATE_ASC)
            .queryParam("startDateTime", fenetre.debut());

    ResultatMultiPage resultat = fetchToutesPages(uriBase, null, "lot");
    if (!resultat.succes()) {
      log.warn(
          "TM fetchArtistesLot ids={} échoué (voir erreur HTTP ci-dessus)", attractionIds.size());
    } else if (dernierQuota.get() != null) {
      log.info(
          "TM fetchArtistesLot ids={} total={}", attractionIds.size(), resultat.concerts().size());
    }
    return resultat.concerts();
  }

  /// Statut de vivacité d'un identifiant TM (phase 1).
  public enum StatutIdTM {
    VIVANT,
    MORT
  }

  /// Candidat de redécouverte pour une salle TM disparue.
  ///
  /// @param id venueId TM candidat
  /// @param nom nom de la salle chez TM
  public record CandidatVenueTM(String id, String nom) {}

  /// Vérifie qu'un venueId TM existe toujours.
  ///
  /// @param venueId identifiant de salle TM
  /// @return {@link StatutIdTM#MORT} si TM répond 404, {@link StatutIdTM#VIVANT} sinon
  public StatutIdTM verifierVenue(String venueId) {
    return healthCheck.verifierVenue(venueId);
  }

  /// Vérifie qu'un attractionId TM existe toujours.
  ///
  /// @param attractionId identifiant d'artiste TM
  /// @return {@link StatutIdTM#MORT} si TM répond 404, {@link StatutIdTM#VIVANT} sinon
  public StatutIdTM verifierAttraction(String attractionId) {
    return healthCheck.verifierAttraction(attractionId);
  }

  /// Récupère le nom actuel d'une attraction TM (health-check du rattachement — distinct de {@link
  /// #verifierAttraction}, qui ne vérifie que la vivacité HTTP de l'identifiant, jamais que le nom
  /// retourné correspond toujours à l'artiste suivi).
  ///
  /// @param attractionId identifiant TM de l'attraction
  /// @return nom actuel retourné par TM, ou {@code null} si introuvable ou en cas d'erreur réseau
  public String recupererNomAttraction(String attractionId) {
    return healthCheck.recupererNomAttraction(attractionId);
  }

  /// Recherche des salles TM candidates par nom (redécouverte active, phase 1).
  ///
  /// @param nom nom de la salle recherchée
  /// @return candidats trouvés (peut être vide)
  public List<CandidatVenueTM> rechercherVenuesParNom(String nom) {
    return healthCheck.rechercherVenuesParNom(nom);
  }

  /// Compte les événements à venir d'une salle TM (validation d'un candidat de redécouverte).
  ///
  /// @param venueId identifiant de salle TM
  /// @return nombre d'événements trouvés, 0 en cas d'erreur
  public int compterEventsVenue(String venueId) {
    return healthCheck.compterEventsVenue(venueId);
  }

  /// Récupère les concerts par région (géolocalisation centre de département).
  ///
  /// @param depts liste de codes département (ex: ["75", "92"])
  /// @param classifs classifications TM à inclure ; par défaut music, opera, musical
  /// @return liste des concerts trouvés pour tous les départements
  @Override
  public List<ConcertBrut> fetchRegion(List<String> depts, List<String> classifs) {
    if (classifs == null) classifs = CLASSIFICATIONS;
    List<ConcertBrut> tous = new ArrayList<>();

    for (int i = 0; i < depts.size(); i++) {
      String dept = depts.get(i);
      CentreDept centre = CentresDepartement.PAR_DEPT.get(dept);
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

        tous.addAll(fetchToutesPages(uriBase, null, "dept=" + dept).concerts());
      }
    }

    return tous;
  }
}
