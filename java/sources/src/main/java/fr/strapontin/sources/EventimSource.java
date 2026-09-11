/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.UtilitairesChaine;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client HTTP pour l'API publique Eventim France.
 *
 * <p>Effectue les appels REST vers l'API exploration Eventim : recherche par artiste (via
 * attractionId) et par région (par département). La pagination est gérée jusqu'à MAX_PAGES.
 *
 * @since 2.0.0
 * @author Kwaite
 */
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

  /** Catégories Eventim correspondant aux spectacles musicaux (concerts et musique classique). */
  public static final List<String> CATEGORIES_REGION =
      List.of("Concerts & Festivals", "Classique & Danse");

  /**
   * {@code User-Agent} honnête de bot — jamais une chaîne {@code Mozilla/5.0...}, voir Javadoc du
   * constructeur par défaut pour la justification complète.
   */
  static final String USER_AGENT = "StrapontinBot/1.0 (+https://strapontin.kwaite.fr)";

  private final RestClient restClient;
  // Compteur cumulé depuis le dernier prélèvement (remonté dans logs_admin par le worker,
  // via un composant partagé).
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();
  // correspondances par containment acceptées depuis le dernier prélèvement — un
  // containment n'est pas nécessairement une erreur (ex. "-M-" → "-M- Matthieu Chedid" est
  // correct) mais reste un signal à vérifier manuellement, jusque-là invisible (log SLF4J niveau
  // INFO uniquement, jamais dans logs_admin).
  private final List<String> containmentsAcceptes = Collections.synchronizedList(new ArrayList<>());

  /**
   * Crée une instance avec un RestClient par défaut incluant les headers Eventim et un timeout
   * explicite (connexion 10s, lecture 30s).
   *
   * <p>Le {@code User-Agent} identifie honnêtement un bot plutôt que de mimer un navigateur :
   * depuis le 09/07/2026, Akamai bloque en 403 tout client dont le {@code User-Agent} prétend être
   * un navigateur ({@code Mozilla/5.0...}, quel que soit le navigateur imité) sans en avoir la
   * signature réelle, alors qu'un {@code User-Agent} non-navigateur passe sans restriction —
   * vérifié par tests répétés depuis des réseaux distincts. Les en-têtes {@code sec-ch-ua*}/{@code
   * Sec-Fetch-*}/{@code Referer}/ {@code Origin} (contournement anti-bot historique, commit
   * 231c026, devenu obsolète) sont retirés en conséquence : incohérents avec un {@code User-Agent}
   * de bot.
   *
   * <p>Aucun {@code Accept-Encoding} explicite : envoyer ce header manuellement fait basculer
   * {@code SimpleClientHttpRequestFactory} (JDK {@code HttpURLConnection}) en mode « décompression
   * à la charge de l'appelant » — Eventim répond alors en gzip sans jamais être décompressé,
   * faisant échouer la désérialisation JSON. En l'absence de ce header, le JDK ajoute son propre
   * {@code Accept-Encoding: gzip} et décompresse la réponse de façon transparente. Bug latent
   * introduit par le 08/07/2026, masqué du 09/07 au correctif par le blocage Akamai.
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
   * Construit un {@link RestClient.Builder} avec les headers par défaut (sans requestFactory ni
   * build), pour permettre à {@link org.springframework.test.web.client.MockRestServiceServer} de
   * se lier à la configuration réelle en test (régression sur {@code User-Agent}/ {@code
   * Accept-Encoding}).
   *
   * @return le builder configuré, prêt à recevoir une requestFactory
   */
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
  }

  @Override
  public String nom() {
    return "eventim";
  }

  /**
   * Retourne le bilan agrégé des appels HTTP Eventim depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * Retourne les correspondances par containment acceptées depuis le dernier prélèvement et vide la
   * liste — contrairement à {@link #preleverBilan}, ce n'est pas un bilan de panne : Eventim ne
   * persiste aucun identifiant ({@link #rechercherAttractionId} est rappelé à chaque cycle), donc
   * un containment mal résolu peut réapparaître à chaque fetch tant qu'il n'est pas corrigé
   * manuellement.
   *
   * @return messages décrivant chaque containment accepté depuis le dernier prélèvement
   */
  public List<String> preleverContainmentsAcceptes() {
    synchronized (containmentsAcceptes) {
      final List<String> copie = List.copyOf(containmentsAcceptes);
      containmentsAcceptes.clear();
      return copie;
    }
  }

  /**
   * Corrige la ville Eventim pour les salles de la région parisienne hors Paris intra-muros.
   *
   * <p>Eventim utilise parfois "Paris" comme libellé régional/marketing au lieu de la commune
   * réelle pour les salles de la petite couronne (ex. La Seine Musicale à Boulogne-Billancourt,
   * Stade de France à Saint-Denis), alors que {@code dept} (dérivé du code postal) reste fiable. Si
   * une incohérence est détectée, tente d'extraire la commune depuis le nom de la salle (forme
   * "Salle - Commune") ; sinon journalise et retourne {@code null} plutôt que la valeur incohérente
   * — une valeur qu'on vient soi-même de qualifier d'incohérente ne doit jamais écraser une valeur
   * déjà connue en base.
   *
   * @param ville ville brute renvoyée par Eventim, peut être {@code null}
   * @param dept département dérivé du code postal, peut être {@code null}
   * @param salle nom de la salle, utilisé comme source alternative de la commune
   * @return ville corrigée, la ville d'origine si aucune incohérence détectée, ou {@code null} si
   *     incohérence détectée sans correction possible
   */
  public static String corrigerVilleEventim(
      final String ville, final String dept, final String salle) {
    return MapeurProduitEventim.corrigerVilleEventim(ville, dept, salle);
  }

  /**
   * Parse une date ISO 8601 Eventim et extrait la date et l'heure locales.
   *
   * @param dateStr chaîne de date ISO 8601
   * @return tableau à deux éléments : [date (yyyy-MM-dd), heure (HH:mm) ou null]
   */
  public static String[] parseDateEventim(final String dateStr) {
    return MapeurProduitEventim.parseDateEventim(dateStr);
  }

  /**
   * Extrait le nom de l'artiste depuis un titre d'événement Eventim.
   *
   * @param titre titre de l'événement Eventim
   * @return nom de l'artiste extrait, ou le titre complet si aucun séparateur trouvé
   */
  public static String extraireNomArtiste(final String titre) {
    return MapeurProduitEventim.extraireNomArtiste(titre);
  }

  /**
   * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom de l'artiste cherché (pour fetchArtiste uniquement), peut être null
   * @return concert extrait, ou null si le produit est invalide
   */
  public static ConcertBrut mapProduit(
      final Map<String, Object> produit, final String artisteFallback) {
    return MapeurProduitEventim.mapper(produit, artisteFallback, null);
  }

  /**
   * Mappe un produit Eventim brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param produit produit Eventim sous forme de Map imbriquée
   * @param artisteFallback nom de l'artiste cherché (pour fetchArtiste uniquement), peut être null
   * @param artisteSourceId identifiant source préfixé de l'artiste (ex. {@code "eventim:12345"}),
   *     null si inconnu (fetch région ou salle)
   * @return concert extrait, ou null si le produit est invalide
   */
  public static ConcertBrut mapProduit(
      final Map<String, Object> produit,
      final String artisteFallback,
      final String artisteSourceId) {
    return MapeurProduitEventim.mapper(produit, artisteFallback, artisteSourceId);
  }

  /**
   * @param succes {@code false} si l'appel HTTP a échoué — distingue une page réellement vide
   *     (succès, 0 résultat) d'un appel qui n'a jamais abouti.
   */
  private record ResultatPageEventim(List<ConcertBrut> concerts, int total, boolean succes) {
    // Page légitimement vide (0 résultat, pas d'échec HTTP) — distinct de ECHEC ci-dessous.
    static final ResultatPageEventim VIDE = new ResultatPageEventim(List.of(), 0, true);
    static final ResultatPageEventim ECHEC = new ResultatPageEventim(List.of(), 0, false);

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
      log.warn("Eventim erreur HTTP", e);
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
      final ConcertBrut c = mapProduit(p, artisteFallback, artisteSourceId);
      if (c != null && (deptFiltre == null || deptFiltre.equals(c.dept()))) {
        concerts.add(c);
      }
    }
    final int total =
        data.get(CLE_TOTAL_RESULTS) != null ? ((Number) data.get(CLE_TOTAL_RESULTS)).intValue() : 0;
    return new ResultatPageEventim(concerts, total, true);
  }

  /**
   * Recherche l'attractionId Eventim correspondant à un terme de recherche.
   *
   * @param terme nom de l'artiste cherché
   * @param identiteConfirmee {@code true} si l'artiste a déjà une identité confirmée par une source
   *     indépendante — désactive le repli par containment, n'accepte plus qu'une correspondance
   *     exacte
   * @return attractionId trouvé, ou {@code null} si aucune correspondance acceptable
   */
  @SuppressWarnings("unchecked")
  private String rechercherAttractionId(final String terme, final boolean identiteConfirmee) {
    final String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/v1/attractions")
            .queryParam(PARAM_WEB_ID, WEB_ID)
            .queryParam(PARAM_LANGUE, "fr")
            .queryParam("search_term", terme)
            .queryParam("top", 5)
            .build()
            .toUriString();

    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return null;
      }
      final List<Map<String, Object>> attractions =
          (List<Map<String, Object>>) data.get("attractions");
      if (attractions == null || attractions.isEmpty()) {
        return null;
      }

      final String termeNorm = Normalisation.normaliserTexte(terme);
      Map<String, Object> exact = null;
      Map<String, Object> parContainment = null;
      for (final Map<String, Object> a : attractions) {
        final String nomNorm = Normalisation.normaliserTexte((String) a.getOrDefault("name", ""));
        // Egalité sans espaces : couvre une contraction de nom propre ("N'Dour" → "n dour") qu'une
        // source omet ("NDour"). Non gaté par identiteConfirmee, plus strict que le containment.
        if (nomNorm.equals(termeNorm)
            || UtilitairesChaine.estEgaliteSansEspaces(nomNorm, termeNorm)) {
          exact = a;
          break;
        }
        // Accepte "terme" contenu dans le nom Eventim (ex. "-M-" dans "-M- Matthieu Chedid") —
        // seulement si l'identité n'est pas déjà confirmée par ailleurs : sinon ce repli
        // approximatif a fini par accepter à répétition un mauvais rapprochement (ex. "Furies"
        // dans "Love & Furies", deux entités distinctes). Seuil sur la longueur brute pour éviter
        // les faux positifs sur un seul caractère.
        if (!identiteConfirmee
            && parContainment == null
            && terme.length() >= 2
            && !termeNorm.isEmpty()
            && nomNorm.contains(termeNorm)) {
          parContainment = a;
        }
      }
      if (exact == null && parContainment != null) {
        final String nomRetourne = (String) parContainment.getOrDefault("name", "");
        log.info(
            "Eventim: correspondance par containment pour terme={} — résultat={} accepté",
            terme,
            nomRetourne);
        containmentsAcceptes.add("terme=" + terme + " → resultat=" + nomRetourne);
        exact = parContainment;
      }
      if (exact == null) {
        final String nomRetourne = (String) attractions.get(0).getOrDefault("name", "");
        log.warn(
            "Eventim: aucune correspondance exacte pour terme={} — premier résultat={} ignoré",
            terme,
            nomRetourne);
        return null;
      }
      Object id = exact.get("attractionId");
      if (id == null) {
        id = exact.get("id");
      }
      return id != null ? String.valueOf(id) : null;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Eventim attractions erreur terme={}", terme, e);
      return null;
    }
  }

  /**
   * @param succes {@code false} si au moins une page a échoué (voir {@link ResultatPageEventim}).
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
      if (page > 1) {
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
      if (page > 1) {
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
   * Récupère les concerts d'un artiste via l'API Eventim.
   *
   * <p>Recherche d'abord l'attractionId Eventim par le nom, puis pagine les produits. Identité non
   * confirmée par une source indépendante : le repli par containment reste autorisé.
   *
   * @param nom nom de l'artiste
   * @return liste des concerts trouvés, vide si l'artiste n'est pas référencé sur Eventim
   */
  @Override
  public List<ConcertBrut> fetchArtiste(final String nom) {
    return fetchArtiste(nom, null, null);
  }

  /**
   * Récupère les concerts d'un artiste via l'API Eventim, en tenant compte de son identité déjà
   * confirmée par une source indépendante si connue.
   *
   * <p>Motif : le repli par containment de {@link #rechercherAttractionId} (ex. "Furies" trouvé
   * dans "Love & Furies") s'est révélé actif et récurrent — chaque cycle de fetch réacceptait le
   * même mauvais rapprochement, défaisant silencieusement tout nettoyage de données fait à la main.
   * Dès que l'artiste a déjà un {@code deezerId} ou un {@code wikidataId} connu (identité confirmée
   * indépendamment), seule une correspondance exacte est acceptée — le repli par containment reste
   * réservé aux artistes dont l'identité n'est pas encore confirmée, cas pour lequel il a été
   * introduit (ex. {@code "-M-"} → {@code "-M- Matthieu Chedid"}).
   *
   * @param nom nom de l'artiste
   * @param deezerId identifiant Deezer déjà connu, ou {@code null} si l'identité n'est pas
   *     confirmée
   * @param wikidataId identifiant Wikidata déjà connu, ou {@code null} si l'identité n'est pas
   *     confirmée
   * @return liste des concerts trouvés, vide si l'artiste n'est pas référencé sur Eventim
   */
  @Override
  public List<ConcertBrut> fetchArtiste(
      final String nom, final String deezerId, final String wikidataId) {
    final boolean identiteConfirmee = deezerId != null || wikidataId != null;
    final String attractionId = rechercherAttractionId(nom, identiteConfirmee);
    if (attractionId == null) {
      log.info("Eventim: artiste non trouvé artiste={}", nom);
      return List.of();
    }
    final ResultatArtisteEventim resultat = fetchProduitsParAttraction(attractionId, nom);
    if (resultat.succes()) {
      log.info("Eventim fetchArtiste artiste={} total={}", nom, resultat.concerts().size());
    } else {
      log.warn("Eventim fetchArtiste artiste={} échoué (voir erreur HTTP ci-dessus)", nom);
    }
    return resultat.concerts();
  }

  /**
   * Récupère les concerts par région pour une liste de départements.
   *
   * <p>Le paramètre {@code classifs} est ignoré : Eventim utilise ses propres catégories ({@link
   * #CATEGORIES_REGION}) qui ne correspondent pas aux classifications Ticketmaster.
   *
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
