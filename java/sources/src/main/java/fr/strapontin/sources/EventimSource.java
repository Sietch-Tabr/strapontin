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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client HTTP pour l'API publique Eventim France (recherche par artiste via
 * attractionId, par lieu et par villes d'une région, pagination jusqu'au
 * nombre de pages annoncé).
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
  private static final String CLE_TOTAL_PAGES = "totalPages";
  // Au-delà, l'API répond 400 ; sans top, pages d'environ 20 produits.
  /* package-private : partagé avec EventimLieuxFetcher (même package). */
  static final int PLAFOND_PAGES = 103;
  // attraction_ids et postal_code exigent top (50 au plus), qui fait ignorer page :
  // une requête par fenêtre de dates, reprise au dernier jour reçu.
  /* package-private : partagé avec EventimLieuxFetcher (même package). */
  static final int TOP_MAX = 50;
  private static final int NB_FENETRES_ARTISTE = 10;
  private static final String PREFIXE_IDENTIFIANT = "eventim:";

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
  private final LieuxEventim lieux;
  private final ProduitsEcartes ecartes = new ProduitsEcartes();

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
    this.lieux =
        new LieuxEventim(
            new EventimLieuxFetcher(
                BASE_URL + "/v1/products",
                WEB_ID,
                uri -> chargerPageProduits(uri, null, null, null),
                this::attendreEntrePagesEventim));
  }

  /**
   * Définit la source des villes interrogées par {@link #fetchRegionCouverte}
   * (aucune par défaut : le fetch région Eventim ne renvoie alors rien).
   * @param fournisseur villes connues par département
   */
  public void definirFournisseurVilles(final FournisseurVillesEventim fournisseur) {
    lieux.definirFournisseurVilles(fournisseur);
  }

  /**
   * Retourne les lieux observés depuis le dernier prélèvement et vide la liste.
   * @return lieux observés (libellé, ville, coordonnées)
   */
  public List<LieuEventim> preleverLieuxObserves() {
    return lieux.preleverLieuxObserves();
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
  private PageProduitsEventim chargerPageProduits(
      final URI uri,
      final String artisteFallback,
      final String deptFiltre,
      final String artisteSourceId) {
    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return parserReponseProduits(data, artisteFallback, deptFiltre, artisteSourceId);
    } catch (ResourceAccessException e) {
      // Panne réseau (DNS, I/O, timeout) : propagée pour que l'appelant ne la confonde
      // pas avec une réponse vide (retry ciblé, absences non comptées).
      compteurBilan.enregistrerErreur();
      LOG.warn("Eventim injoignable : {}", e.getMessage());
      throw e;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Eventim erreur HTTP : {}", e.getMessage());
      return PageProduitsEventim.ECHEC;
    }
  }

  @SuppressWarnings("unchecked")
  private PageProduitsEventim parserReponseProduits(
      final Map<String, Object> data,
      final String artisteFallback,
      final String deptFiltre,
      final String artisteSourceId) {
    if (data == null) {
      return PageProduitsEventim.VIDE;
    }
    final List<Map<String, Object>> produits = (List<Map<String, Object>>) data.get(CLE_PRODUCTS);
    if (produits == null || produits.isEmpty()) {
      return PageProduitsEventim.VIDE;
    }

    produits.forEach(lieux::relever);
    final List<Map<String, Object>> retenus =
        artisteFallback != null ? ecartes.retenir(produits, artisteFallback) : produits;
    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final Map<String, Object> p : retenus) {
      final ConcertBrut concert = mapProduit(p, artisteFallback, artisteSourceId);
      if (concert != null && (deptFiltre == null || deptFiltre.equals(concert.dept()))) {
        concerts.add(concert);
      }
    }
    return new PageProduitsEventim(
        concerts,
        entier(data, CLE_TOTAL_RESULTS),
        entier(data, CLE_TOTAL_PAGES),
        produits.size(),
        true);
  }

  /**
   * @return produits écartés des récupérations par artiste, prélevés par le worker
   */
  public ProduitsEcartes produitsEcartes() {
    return ecartes;
  }

  private static int entier(final Map<String, Object> data, final String cle) {
    return data.get(cle) instanceof Number nombre ? nombre.intValue() : 0;
  }

  /**
   * @param succes {@code false} si au moins une page a échoué (voir
   *     {@link PageProduitsEventim} ).
   */
  private record ResultatArtisteEventim(List<ConcertBrut> concerts, boolean succes) {
    /* package-private : appelé par fetchArtiste (même classe). */
    List<ConcertBrut> concertsOuEchec(final String contexte) {
      if (!succes) {
        throw new ReponseIncompleteException(contexte, concerts, null);
      }
      return concerts;
    }
  }

  private ResultatArtisteEventim fetchProduitsParAttraction(
      final String attractionId, final String artisteFallback) {
    final LocalDate dateFin = LocalDate.now().plusMonths(24);
    final List<ConcertBrut> concerts = new ArrayList<>();
    final String artisteSourceId = "eventim:" + attractionId;
    LocalDate debut = LocalDate.now();
    for (int fenetre = 0; fenetre < NB_FENETRES_ARTISTE; fenetre++) {
      if (fenetre > 0 && !attendreEntrePagesEventim()) {
        return new ResultatArtisteEventim(concerts, false);
      }
      final URI uri =
          UriSources.encoder(
              UriComponentsBuilder.fromUriString(BASE_URL + "/v1/products")
                  .queryParam(PARAM_WEB_ID, WEB_ID)
                  .queryParam(PARAM_LANGUE, "fr")
                  .queryParam("attraction_ids", attractionId)
                  .queryParam("date_from", debut.toString())
                  .queryParam("date_to", dateFin.toString())
                  .queryParam("sort", "DateAsc")
                  .queryParam("top", TOP_MAX));
      final PageProduitsEventim resultat =
          chargerPageProduits(uri, artisteFallback, null, artisteSourceId);
      if (!resultat.succes()) {
        return new ResultatArtisteEventim(concerts, false);
      }
      concerts.addAll(resultat.concerts());
      final LocalDate derniere =
          resultat.concerts().stream()
              .map(c -> LocalDate.parse(c.date()))
              .max(LocalDate::compareTo)
              .orElse(null);
      // Reprise au dernier jour reçu (doublons dédoublonnés en aval).
      if (resultat.nbProduits() < TOP_MAX || derniere == null || !derniere.isAfter(debut)) {
        break;
      }
      debut = derniere;
    }
    return new ResultatArtisteEventim(concerts, true);
  }

  /**
   * Récupère les concerts d'un artiste via l'API Eventim (recherche
   * l'attractionId par nom, puis pagine ; containment autorisé, identité
   * non confirmée par une source indépendante).
   * @param nom nom de l'artiste
   * @return liste des concerts trouvés, vide si l'artiste n'est pas référencé
   * @throws ReponseIncompleteException si une page a échoué, avec les concerts reçus
   */
  @Override
  public List<ConcertBrut> fetchArtiste(final String nom) {
    return fetchArtiste(nom, null, null);
  }

  /**
   * Sans containment si deezerId/wikidataId connu (contamination récurrente).
   * @param nom nom de l'artiste
   * @param deezerId identifiant Deezer déjà connu, ou {@code null}
   * @param wikidataId identifiant Wikidata déjà connu, ou {@code null}
   * @return liste des concerts trouvés, vide si non référencé
   * @throws ReponseIncompleteException si une page a échoué, avec les concerts reçus
   */
  @Override
  public List<ConcertBrut> fetchArtiste(
      final String nom, final String deezerId, final String wikidataId) {
    return fetchArtiste(nom, deezerId, wikidataId, List.of());
  }

  /**
   * Identité confirmée : un nom Eventim contenant le nom suivi n'est retenu
   * que si le reste est un autre nom connu (« -M- Matthieu Chedid »).
   * @param autresNomsConnus libellé et alias Wikidata de l'artiste
   * @return concerts trouvés, vide si non référencé
   * @throws ReponseIncompleteException si une page a échoué, avec les concerts reçus
   */
  @Override
  public List<ConcertBrut> fetchArtiste(
      final String nom,
      final String deezerId,
      final String wikidataId,
      final List<String> autresNomsConnus) {
    final boolean identiteConfirmee = deezerId != null || wikidataId != null;
    final String attractionId =
        attractionResolver.rechercherAttractionId(nom, identiteConfirmee, autresNomsConnus);
    if (attractionId == null) {
      LOG.info("Eventim: artiste non trouvé artiste={}", nom);
      return List.of();
    }
    final List<ConcertBrut> concerts =
        fetchProduitsParAttraction(attractionId, nom)
            .concertsOuEchec("Eventim fetchArtiste artiste=" + nom);
    LOG.info("Eventim fetchArtiste artiste={} total={}", nom, concerts.size());
    return concerts;
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
    return fetchRegionCouverte(depts, classifs).concerts();
  }

  /**
   * Interroge les villes connues des départements ({@code city_names}, seul
   * filtre géographique réellement appliqué par l'API) ; aucune ville connue
   * = aucune garantie de couverture.
   * @param depts liste de codes département
   * @param classifs ignoré — toujours {@link #CATEGORIES_REGION}
   * @return concerts des départements et dernière date couverte
   */
  @Override
  public ResultatRegion fetchRegionCouverte(final List<String> depts, final List<String> classifs) {
    return lieux.fetchRegion(depts, CATEGORIES_REGION);
  }

  @Override
  public boolean accepteIdentifiantSalle(final String identifiant) {
    return identifiant != null && identifiant.startsWith(PREFIXE_IDENTIFIANT);
  }

  /**
   * Eventim n'expose aucun identifiant de lieu interrogeable : sans libellé
   * exact, aucune requête n'est possible.
   * @param identifiant identifiant préfixé ({@code eventim:internal:...})
   * @param libelle nom exact du lieu chez Eventim
   * @return concerts à venir du lieu, vide si le libellé est inconnu
   * @throws ReponseIncompleteException si une page a échoué, avec les concerts reçus
   */
  @Override
  public List<ConcertBrut> fetchSalle(final String identifiant, final String libelle) {
    return lieux.fetchLieu(libelle);
  }

  @Override
  public int joursCouvertsParSalle() {
    return EventimLieuxFetcher.JOURS_COUVERTS_SALLE;
  }

  /**
   * Compte les concerts à venir d'un lieu (vérification d'un libellé saisi à
   * la main, le filtre {@code venue_names} exigeant le nom exact).
   * @param libelle nom exact du lieu chez Eventim
   * @return nombre de concerts (≥ 0), ou -1 si Eventim n'a pas répondu
   */
  public int compterConcertsLieu(final String libelle) {
    return lieux.compterConcerts(libelle);
  }
}
