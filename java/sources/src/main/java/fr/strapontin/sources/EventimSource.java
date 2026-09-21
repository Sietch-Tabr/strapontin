/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client HTTP pour l'API publique Eventim France (recherche par artiste via
 * attractionId, et par région/département, pagination gérée jusqu'à
 * MAX_PAGES).
 *
 * @since 2.0.0
 * @author Kwaite
 */
public class EventimSource implements SourceConcerts, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(EventimSource.class);

  private static final String BASE_URL =
      "https://public-api.eventim.com/websearch/search/api/exploration";
  private static final String WEB_ID = "web__eventim-fr";
  private static final String PARAM_WEB_ID = "webId";
  private static final String PARAM_LANGUE = "language";
  private static final String CLE_PRODUCTS = "products";
  private static final String CLE_TOTAL_RESULTS = "totalResults";
  private static final int TAILLE_PAGE = 50;
  private static final int MAX_PAGES = 10;
  private static final int PREMIERE_PAGE = 1;

  /**
   * Catégories Eventim correspondant aux spectacles musicaux (concerts et
   * musique classique).
   */
  public static final List<String> CATEGORIES_REGION =
      List.of("Concerts & Festivals", "Classique & Danse");

  /**
   * {@code User-Agent} honnête de bot — jamais une chaîne
   * {@code Mozilla/5.0...} , voir Javadoc du constructeur par défaut pour la
   * justification complète.
   */
  /* package-private : visibilité élargie pour EventimSourceTest (même
   * package). */
  static final String USER_AGENT = "StrapontinBot/1.0 (+https://strapontin.kwaite.fr)";

  private final RestClient restClient;
  // Compteur cumulé depuis le dernier prélèvement (remonté dans logs_admin par
  // le worker, via un composant partagé).
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();
  private final EventimAttractionResolver attractionResolver;

  /**
   * Crée une instance avec un RestClient par défaut (headers Eventim,
   * timeout 10s/30s). User-Agent honnête + sans Accept-Encoding explicite
   * (contournement Akamai #904, détails wiki Sources-de-données).
   */
  public EventimSource() {
    this(
        builderAvecHeadersParDefaut()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build());
  }

  /**
   * Construit un {@link RestClient.Builder} avec les headers par défaut
   * (sans requestFactory ni build), pour lier MockRestServiceServer à la
   * config réelle en test (régression User-Agent/Accept-Encoding).
   * @return le builder configuré, prêt à recevoir une requestFactory
   */
  /* package-private : visibilité élargie pour EventimSourceTest (même
   * package). */
  static RestClient.Builder builderAvecHeadersParDefaut() {
    return RestClient.builder()
        .defaultHeader("Accept", "application/json, text/plain, */*")
        .defaultHeader("User-Agent", USER_AGENT)
        .defaultHeader("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
        .defaultHeader("Connection", "keep-alive");
  }

  /**
   * Crée une instance avec un RestClient personnalisé (injection de test).
   *
   * @param restClient client HTTP Spring à utiliser
   */
  public EventimSource(final RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient");
    this.attractionResolver =
        new EventimAttractionResolver(this.restClient, compteurBilan, BASE_URL, WEB_ID);
  }

  @Override
  public String nom() {
    return "eventim";
  }

  /**
   * Retourne le bilan agrégé des appels HTTP Eventim depuis le dernier
   * prélèvement et remet les compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * Retourne les correspondances par containment acceptées et vide la
   * liste — pas un bilan de panne : Eventim ne persiste aucun identifiant
   * (rappelé à chaque cycle), un containment mal résolu peut donc
   * réapparaître tant qu'il n'est pas corrigé manuellement.
   * @return messages décrivant chaque containment accepté
   */
  public List<String> preleverContainmentsAcceptes() {
    return attractionResolver.preleverContainmentsAcceptes();
  }

  /**
   * Corrige la ville Eventim ("Paris" marketing pour la petite couronne) via
   * dept ou le nom de salle ("Salle - Commune"), jamais de valeur incohérente.
   * @param ville ville brute renvoyée par Eventim, peut être {@code null}
   * @param dept département dérivé du code postal, peut être {@code null}
   * @param salle nom de la salle, source alternative de la commune
   * @return ville corrigée, ou {@code null} si incohérence non corrigeable
   */
  public static String corrigerVilleEventim(
      final String ville, final String dept, final String salle) {
    return MapeurProduitEventim.corrigerVilleEventim(ville, dept, salle);
  }

  /**
   * Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
   *
   * @param dateStr chaîne de date ISO 8601
   * @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou
   *     null]
   */
  public static String[] parseDateEventim(final String dateStr) {
    return MapeurProduitEventim.parseDateEventim(dateStr);
  }

  /**
   * Extrait le nom de l'artiste depuis un titre d'événement Eventim.
   *
   * @param titre titre de l'événement Eventim
   * @return nom de l'artiste extrait, ou le titre complet si aucun séparateur
   *     trouvé
   */
  public static String extraireNomArtiste(final String titre) {
    return MapeurProduitEventim.extraireNomArtiste(titre);
  }

  /**
   * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom de l'artiste cherché (pour fetchArtiste
   *     uniquement), peut être null
   * @return concert extrait, ou null si le produit est invalide
   */
  public static ConcertBrut mapProduit(
      final Map<String, Object> produit, final String artisteFallback) {
    return MapeurProduitEventim.mapper(produit, artisteFallback, null);
  }

  /**
   * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom de l'artiste cherché (fetchArtiste), ou null
   * @param artisteSourceId identifiant préfixé (ex. "eventim:12345"), ou null
   * @return concert extrait, ou null si le produit est invalide
   */
  public static ConcertBrut mapProduit(
      final Map<String, Object> produit,
      final String artisteFallback,
      final String artisteSourceId) {
    return MapeurProduitEventim.mapper(produit, artisteFallback, artisteSourceId);
  }

  /**
   * @param succes {@code false} si l'appel HTTP a échoué — distingue une page
   *     réellement vide (succès, 0 résultat) d'un appel qui n'a jamais abouti.
   */
  private record ResultatPageEventim(List<ConcertBrut> concerts, int total, boolean succes) {
    // Page légitimement vide (0 résultat, pas d'échec HTTP) — distinct de ECHEC
    // ci-dessous.
    /* package-private : membre du record privé ResultatPageEventim, sans portée
     * réelle hors de EventimSource. */
    static final ResultatPageEventim VIDE = new ResultatPageEventim(List.of(), 0, true);
    /* package-private : membre du record privé ResultatPageEventim, sans portée
     * réelle hors de EventimSource. */
    static final ResultatPageEventim ECHEC = new ResultatPageEventim(List.of(), 0, false);

    /* package-private : membre du record privé ResultatPageEventim, sans portée
     * réelle hors de EventimSource. */
    boolean estVide() {
      return concerts.isEmpty();
    }
  }

  // Thread.sleep est la seule primitive de cadencement synchrone bloquant
  // disponible — pas un contexte de requête J2EE (appel HTTP sortant en job
  // Quartz), la justification de la règle ne s'applique pas ici.
  @SuppressWarnings("PMD.DoNotUseThreads")
  private boolean attendreEntrePagesEventim() {
    try {
      Thread.sleep(200);
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private ResultatPageEventim chargerPageProduits(
      final URI uri,
      final String artisteFallback,
      final String deptFiltre,
      final String artisteSourceId) {
    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return parserReponseProduits(data, artisteFallback, deptFiltre, artisteSourceId);
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Eventim erreur HTTP : {}", e.getMessage());
      return ResultatPageEventim.ECHEC;
    }
  }

  @SuppressWarnings("unchecked")
  private ResultatPageEventim parserReponseProduits(
      final Map<String, Object> data,
      final String artisteFallback,
      final String deptFiltre,
      final String artisteSourceId) {
    if (data == null) {
      return ResultatPageEventim.VIDE;
    }
    final List<Map<String, Object>> produits = (List<Map<String, Object>>) data.get(CLE_PRODUCTS);
    if (produits == null || produits.isEmpty()) {
      return ResultatPageEventim.VIDE;
    }

    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final Map<String, Object> p : produits) {
      final ConcertBrut concert = mapProduit(p, artisteFallback, artisteSourceId);
      if (concert != null && (deptFiltre == null || deptFiltre.equals(concert.dept()))) {
        concerts.add(concert);
      }
    }
    final int total =
        data.get(CLE_TOTAL_RESULTS) != null ? ((Number) data.get(CLE_TOTAL_RESULTS)).intValue() : 0;
    return new ResultatPageEventim(concerts, total, true);
  }

  /**
   * @param succes {@code false} si au moins une page a échoué (voir
   *     {@link ResultatPageEventim} ).
   */
  private record ResultatArtisteEventim(List<ConcertBrut> concerts, boolean succes) {}

  private ResultatArtisteEventim fetchProduitsParAttraction(
      final String attractionId, final String artisteFallback) {
    final LocalDate dateDebut = LocalDate.now();
    final LocalDate dateFin = dateDebut.plusMonths(24);
    final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    final List<ConcertBrut> concerts = new ArrayList<>();
    boolean continuer = true;
    boolean succes = true;
    final String artisteSourceId = "eventim:" + attractionId;

    for (int page = 1; page <= MAX_PAGES && continuer; page++) {
      if (page > PREMIERE_PAGE) {
        continuer = attendreEntrePagesEventim();
      }
      if (continuer) {
        final String uri =
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
        final ResultatPageEventim resultat =
            chargerPageProduits(URI.create(uri), artisteFallback, null, artisteSourceId);
        if (!resultat.succes()) {
          succes = false;
        }
        concerts.addAll(resultat.concerts());
        continuer = !resultat.estVide() && concerts.size() < resultat.total();
      }
    }

    return new ResultatArtisteEventim(concerts, succes);
  }

  private List<ConcertBrut> fetchProduitsParRegion(
      final String dept, final List<String> classifications) {
    final LocalDate dateDebut = LocalDate.now();
    final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    final List<ConcertBrut> concerts = new ArrayList<>();
    boolean continuer = true;

    for (int page = 1; page <= MAX_PAGES && continuer; page++) {
      if (page > PREMIERE_PAGE) {
        continuer = attendreEntrePagesEventim();
      }
      if (continuer) {
        UriComponentsBuilder uriBuilder =
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
        for (final String c : classifications) {
          uriBuilder = uriBuilder.queryParam("categories", c);
        }
        final URI uri = uriBuilder.build().encode().toUri();
        final ResultatPageEventim resultat = chargerPageProduits(uri, null, dept, null);
        concerts.addAll(resultat.concerts());
        continuer = !resultat.estVide() && concerts.size() < resultat.total();
      }
    }

    return concerts;
  }

  /**
   * Récupère les concerts d'un artiste via l'API Eventim (recherche
   * l'attractionId par nom, puis pagine ; containment autorisé, identité
   * non confirmée par une source indépendante).
   * @param nom nom de l'artiste
   * @return liste des concerts trouvés, vide si l'artiste n'est pas référencé
   */
  @Override
  public List<ConcertBrut> fetchArtiste(final String nom) {
    return fetchArtiste(nom, null, null);
  }

  /**
   * Comme {@link #fetchArtiste(String)}, désactive le containment si
   * deezerId/wikidataId connu (évite la contamination récurrente, wiki).
   * @param nom nom de l'artiste
   * @param deezerId identifiant Deezer déjà connu, ou {@code null}
   * @param wikidataId identifiant Wikidata déjà connu, ou {@code null}
   * @return liste des concerts trouvés, vide si non référencé
   */
  @Override
  public List<ConcertBrut> fetchArtiste(
      final String nom, final String deezerId, final String wikidataId) {
    final boolean identiteConfirmee = deezerId != null || wikidataId != null;
    final String attractionId = attractionResolver.rechercherAttractionId(nom, identiteConfirmee);
    if (attractionId == null) {
      LOG.info("Eventim: artiste non trouvé artiste={}", nom);
      return List.of();
    }
    final ResultatArtisteEventim resultat = fetchProduitsParAttraction(attractionId, nom);
    if (resultat.succes()) {
      LOG.info("Eventim fetchArtiste artiste={} total={}", nom, resultat.concerts().size());
    } else {
      LOG.warn("Eventim fetchArtiste artiste={} échoué (voir erreur HTTP ci-dessus)", nom);
    }
    return resultat.concerts();
  }

  /**
   * Récupère les concerts par région (classifs ignoré — Eventim utilise
   * ses propres catégories {@link #CATEGORIES_REGION}).
   * @param depts liste de codes département (ex: ["75", "92"])
   * @param classifs ignoré — toujours {@link #CATEGORIES_REGION}
   * @return liste des concerts trouvés pour tous les départements
   */
  @Override
  public List<ConcertBrut> fetchRegion(final List<String> depts, final List<String> classifs) {
    final List<String> categories = CATEGORIES_REGION;
    final List<ConcertBrut> tous = new ArrayList<>();

    for (final String dept : depts) {
      final List<ConcertBrut> concerts = fetchProduitsParRegion(dept, categories);
      tous.addAll(concerts);
      if (!attendreEntrePagesEventim()) {
        break;
      }
    }

    return tous;
  }
}
