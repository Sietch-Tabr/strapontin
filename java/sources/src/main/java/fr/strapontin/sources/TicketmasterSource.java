/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.QuotaTM;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client HTTP pour l'API Ticketmaster Discovery v2 — par artiste, par
 * salle (venueId), par lot d'attractionIds, par région. Respecte le
 * rate limit de 5 req/s ; quota journalier extrait des headers {@code
 * Rate-Limit-*}.
 * @since 2.0.0
 * @author Kwaite
 */
public class TicketmasterSource implements SourceAvecFetchLot, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterSource.class);

  // Package-private (pas private) : réutilisé par TicketmasterRegionFetcher.
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";

  /**
   * Classifications Ticketmaster filtrées lors des fetchs (musique, opéra,
   * comédie musicale). Valeur canonique partagée — référencée par le worker
   * plutôt que redéfinie.
   */
  public static final List<String> CLASSIFICATIONS = List.of("music", "opera", "musical");

  /**
   * Préfixe abrégé attendu par {@link #accepteIdentifiantSalle} /{@link
   * #fetchSalle}, distinct de {@link #nom()} ({@code "ticketmaster"}, valeur
   * canonique stockée dans {@code salle_sources.source}). Référencée par les
   * appelants qui construisent l'identifiant préfixé à partir de cette colonne
   * — sans cette traduction, {@code fetchSalle} n'est jamais invoqué (mismatch
   * de préfixe silencieux, aucune exception).
   */
  public static final String PREFIXE_IDENTIFIANT = "tm";

  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final int TAILLE_PAGE_REGION = 200;
  // TM applique un Spike Arrest strict de 200ms minimum entre requêtes, sans
  // tolérance de rafale (maxBurstMessageCount=1.0) : le cadenceur n'espace que
  // les instants de départ côté client, la latence réseau peut donc faire
  // percevoir deux appels espacés de 250ms comme trop rapprochés côté serveur.
  // Marge doublée à 100ms au-dessus du minimum strict.
  //
  // Public : réutilisé par les cadenceurs partagés inter-processus (web,
  // worker) pour rester aligné sur ce même délai plutôt que de dupliquer la
  // valeur.
  public static final int DELAI_MIN_ENTRE_APPELS_MS = 300;
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String CHEMIN_EVENTS = "/events.json";
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String TRI_DATE_ASC = "date,asc";
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String PARAM_SIZE = "size";
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String PARAM_SORT = "sort";
  /* package-private : partagée par TicketmasterRegionFetcher (même package). */
  static final String PARAM_START_DATE_TIME = "startDateTime";

  private final String apiKey;
  private final RestClient restClient;
  private final EtatFetchTM etatFetch = new EtatFetchTM();
  private final CadenceurAppels cadenceur;
  private final TicketmasterHealthCheck healthCheck;
  private final TicketmasterPageFetcher pageFetcher;
  private final TicketmasterAttractionResolver attractionResolver;

  /**
   * Crée une instance avec la clé API fournie, un RestClient par défaut
   * (timeout connexion 10s, lecture 30s) et un cadenceur en mémoire
   * (mono-processus).
   *
   * @param apiKey clé API Ticketmaster Discovery v2
   */
  public TicketmasterSource(final String apiKey) {
    this(apiKey, new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS));
  }

  /**
   * Crée une instance avec la clé API et un cadenceur personnalisé —
   * utilisé en production pour un cadenceur partagé inter-processus ({@link
   * CadenceurAppels}, TM aussi appelé directement par le module web).
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   */
  public TicketmasterSource(final String apiKey, final CadenceurAppels cadenceur) {
    this(
        apiKey,
        RestClient.builder()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build(),
        cadenceur);
  }

  /**
   * Crée une instance avec la clé API et un RestClient personnalisé (injection
   * de test), cadencée en mémoire.
   *
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param restClient client HTTP Spring à utiliser
   */
  public TicketmasterSource(final String apiKey, final RestClient restClient) {
    this(apiKey, restClient, new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS));
  }

  /**
   * Crée une instance avec la clé API, le RestClient et le cadenceur
   * personnalisés (injection de test).
   *
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param restClient client HTTP Spring à utiliser
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   */
  /* package-private : maillon intermédiaire de la chaîne d'injection de test,
   * jamais appelé hors de cette classe/des tests du même package. */
  TicketmasterSource(
      final String apiKey, final RestClient restClient, final CadenceurAppels cadenceur) {
    this(
        apiKey,
        restClient,
        TicketmasterAttractionResolver.creerCacheAvertissementsParDefaut(),
        cadenceur);
  }

  /**
   * Crée une instance avec la clé API, le RestClient et le cache de throttle
   * des avertissements personnalisés (injection de test), cadencée en mémoire.
   *
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param restClient client HTTP Spring à utiliser
   * @param avertissementsAttractionIntrouvable cache de throttle à utiliser
   */
  /* package-private : maillon intermédiaire de la chaîne d'injection de test,
   * jamais appelé hors de cette classe/des tests du même package. */
  TicketmasterSource(
      final String apiKey,
      final RestClient restClient,
      final Cache<String, Boolean> avertissementsAttractionIntrouvable) {
    this(
        apiKey,
        restClient,
        avertissementsAttractionIntrouvable,
        new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS));
  }

  /**
   * Crée une instance en spécifiant individuellement chacune de ses
   * dépendances (injection de test complète).
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param restClient client HTTP Spring à utiliser
   * @param avertissementsAttractionIntrouvable cache de throttle à utiliser
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   */
  /* package-private : constructeur d'injection de test complète, jamais appelé
   * hors des tests du même package. */
  TicketmasterSource(
      final String apiKey,
      final RestClient restClient,
      final Cache<String, Boolean> avertissementsAttractionIntrouvable,
      final CadenceurAppels cadenceur) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    this.restClient = Objects.requireNonNull(restClient, "restClient");
    Objects.requireNonNull(
        avertissementsAttractionIntrouvable, "avertissementsAttractionIntrouvable");
    this.cadenceur = Objects.requireNonNull(cadenceur, "cadenceur");
    this.healthCheck = new TicketmasterHealthCheck(this.apiKey, this.restClient, this.cadenceur);
    this.pageFetcher =
        new TicketmasterPageFetcher(
            this.restClient, this.apiKey, this.cadenceur, etatFetch, TAILLE_PAGE_REGION);
    this.attractionResolver =
        new TicketmasterAttractionResolver(
            this.restClient,
            this.apiKey,
            BASE_URL,
            this.cadenceur,
            avertissementsAttractionIntrouvable);
  }

  /**
   * Retourne le dernier quota TM extrait des headers HTTP, ou {@code null} si
   * aucun appel n'a été fait.
   *
   * @return quota courant ou null
   */
  public QuotaTM obtenirQuota() {
    return etatFetch.quota();
  }

  /**
   * Retourne le bilan agrégé appels/erreurs HTTP depuis le dernier prélèvement
   * et remet les compteurs à zéro. Le nombre de concerts manqués (pagination
   * plafonnée) reste distinct, voir {@link #preleverConcertsManques()} .
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    return etatFetch.preleverBilan();
  }

  /**
   * Retourne le nombre cumulé de concerts non récupérés par plafonnement de la
   * pagination (pagination bornée à {@code NB_PAGES_MAX} ) depuis le dernier
   * prélèvement, et remet le compteur à zéro. Métrique spécifique à TM,
   * distincte du bilan appels/erreurs commun.
   *
   * @return le nombre de concerts manqués depuis le dernier prélèvement
   */
  public int preleverConcertsManques() {
    return etatFetch.preleverConcertsManques();
  }

  /**
   * Extrait la date et l'heure d'un événement TM depuis les champs
   * localDate / localTime.
   * @param dateStr valeur de localDate (format ISO 8601, au moins 10 chars)
   * @param heureStr valeur de localTime (null ou "00:00:00" si absente)
   * @return tableau [date, heure] où heure peut être null
   */
  public static String[] parseDateTM(final String dateStr, final String heureStr) {
    return MapeurEvenementTM.parseDateTM(dateStr, heureStr);
  }

  /**
   * Mappe un événement TM brut (JSON désérialisé) vers un [ConcertBrut].
   *
   * @param evt événement TM sous forme de Map imbriquée
   * @param artisteFallback nom recherché (fetch:artiste), null sinon
   * @return concert extrait, ou null si l'événement est invalide ou hors périmètre
   */
  public static ConcertBrut mapEvenement(
      final Map<String, Object> evt, final String artisteFallback) {
    return MapeurEvenementTM.mapper(evt, artisteFallback);
  }

  /**
   * Retire toute clé API ({@code apikey=...}) d'un message avant
   * journalisation — {@code ResourceAccessException} embarque l'URI
   * complète (query string incluse) dans son propre message.
   * @param message message à assainir, peut être null
   * @return le message avec toute clé API masquée, ou null si message null
   */
  /* package-private : appelé par TicketmasterAttractionResolver/
   * TicketmasterHealthCheck/TicketmasterPageFetcher (même package). */
  static String redigerApiKey(final String message) {
    return message == null ? null : message.replaceAll("apikey=[^&\\s\"]+", "apikey=***");
  }

  @Override
  public String nom() {
    return "ticketmaster";
  }

  @Override
  public boolean accepteIdentifiantSalle(final String identifiant) {
    return identifiant != null && identifiant.startsWith(PREFIXE_IDENTIFIANT + ":");
  }

  /**
   * Récupère les concerts d'un artiste par mot-clé (une seule page TM).
   *
   * @param nom nom de l'artiste recherché
   * @return liste des concerts trouvés
   */
  @Override
  public List<ConcertBrut> fetchArtiste(final String nom) {
    final FenetreTempsTM fenetre = FenetreTempsTM.obtenirFenetreTemps();
    final UriComponentsBuilder uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("keyword", nom)
            .queryParam("classificationName", CLASSIFICATIONS.toArray())
            .queryParam(PARAM_SIZE, TAILLE_PAGE_REGION)
            .queryParam(PARAM_SORT, TRI_DATE_ASC)
            .queryParam(PARAM_START_DATE_TIME, fenetre.debut());

    final TicketmasterPageFetcher.ResultatPage resultat = pageFetcher.fetchPage(uriBase, 0, nom);
    if (resultat.quota() != null) {
      etatFetch.mettreAJourQuota(resultat.quota());
    }
    if (resultat.succes()) {
      LOG.info("TM fetchArtiste artiste={} total={}", nom, resultat.total());
    } else {
      LOG.warn("TM fetchArtiste artiste={} échoué (voir erreur HTTP ci-dessus)", nom);
    }
    return resultat.concerts();
  }

  /**
   * Récupère les concerts d'une salle TM par identifiant préfixé ({@code
   * "tm:rZFSnyZdFvA"}) ou brut ({@code "rZFSnyZdFvA"}).
   * @param identifiant identifiant de la salle (préfixé "tm:" ou brut)
   * @return liste des concerts à venir dans cette salle
   */
  @Override
  public List<ConcertBrut> fetchSalle(final String identifiant) {
    final String venueId =
        identifiant.startsWith(PREFIXE_IDENTIFIANT + ":")
            ? identifiant.substring(PREFIXE_IDENTIFIANT.length() + 1)
            : identifiant;
    final FenetreTempsTM fenetre = FenetreTempsTM.obtenirFenetreTemps();
    final UriComponentsBuilder uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("venueId", venueId)
            .queryParam(PARAM_SIZE, TAILLE_PAGE_REGION)
            .queryParam(PARAM_SORT, TRI_DATE_ASC)
            .queryParam(PARAM_START_DATE_TIME, fenetre.debut())
            .queryParam("endDateTime", fenetre.fin());

    final TicketmasterPageFetcher.ResultatMultiPage resultat =
        pageFetcher.fetchToutesPages(uriBase, null, "salle=" + venueId);
    if (resultat.succes()) {
      LOG.info("TM fetchSalle venueId={} total={}", venueId, resultat.concerts().size());
    } else {
      LOG.warn("TM fetchSalle venueId={} échoué (voir erreur HTTP ci-dessus)", venueId);
    }
    return resultat.concerts();
  }

  /**
   * Recherche l'attractionId TM pour un artiste par son nom — parcourt
   * jusqu'à 10 résultats pour la première correspondance exacte normalisée
   * (TM peut trier par popularité, ex. "Shy" avant "Shy'm").
   * @param nom nom de l'artiste
   * @return attractionId TM, ou null si aucun résultat exact des 10 premiers
   */
  public String rechercherAttractionId(final String nom) {
    return attractionResolver.rechercherAttractionId(nom);
  }

  /**
   * Récupère les concerts d'un lot d'artistes TM par leurs attractionIds.
   *
   * @param attractionIds liste d'identifiants TM d'attractions
   * @return liste des concerts trouvés pour tous les artistes du lot
   */
  @Override
  public List<ConcertBrut> fetchArtistesLot(final List<String> attractionIds) {
    if (attractionIds.isEmpty()) {
      return List.of();
    }
    // Valeur exacte envoyée (pas seulement le nombre) — permet de corréler
    // après coup un identifiant attendu (base) avec celui réellement transmis
    // en cas d'écart constaté.
    LOG.info("TM fetchArtistesLot ids envoyés={}", attractionIds);
    final FenetreTempsTM fenetre = FenetreTempsTM.obtenirFenetreTemps();
    final UriComponentsBuilder uriBase =
        UriComponentsBuilder.fromUriString(BASE_URL + CHEMIN_EVENTS)
            .queryParam("attractionId", String.join(",", attractionIds))
            .queryParam(PARAM_SIZE, TAILLE_PAGE_REGION)
            .queryParam(PARAM_SORT, TRI_DATE_ASC)
            .queryParam(PARAM_START_DATE_TIME, fenetre.debut());

    final TicketmasterPageFetcher.ResultatMultiPage resultat =
        pageFetcher.fetchToutesPages(uriBase, null, "lot");
    if (resultat.succes()) {
      if (etatFetch.quota() != null) {
        LOG.info(
            "TM fetchArtistesLot ids={} total={}",
            attractionIds.size(),
            resultat.concerts().size());
      }
    } else {
      LOG.warn(
          "TM fetchArtistesLot ids={} échoué (voir erreur HTTP ci-dessus)", attractionIds.size());
    }
    return resultat.concerts();
  }

  /** Statut de vivacité d'un identifiant TM (phase 1). */
  public enum StatutIdTM {
    VIVANT,
    MORT
  }

  /**
   * Candidat de redécouverte pour une salle TM disparue.
   *
   * @param id venueId TM candidat
   * @param nom nom de la salle chez TM
   */
  public record CandidatVenueTM(String id, String nom) {}

  /**
   * Vérifie qu'un venueId TM existe toujours.
   *
   * @param venueId identifiant de salle TM
   * @return {@link StatutIdTM#MORT} si TM répond 404, {@link StatutIdTM#VIVANT}
   *     sinon
   */
  public StatutIdTM verifierVenue(final String venueId) {
    return healthCheck.verifierVenue(venueId);
  }

  /**
   * Vérifie qu'un attractionId TM existe toujours.
   *
   * @param attractionId identifiant d'artiste TM
   * @return {@link StatutIdTM#MORT} si TM répond 404, {@link StatutIdTM#VIVANT}
   *     sinon
   */
  public StatutIdTM verifierAttraction(final String attractionId) {
    return healthCheck.verifierAttraction(attractionId);
  }

  /**
   * Récupère le nom actuel d'une attraction TM (health-check du
   * rattachement, distinct de {@link #verifierAttraction} qui ne vérifie
   * que la vivacité HTTP, jamais que le nom correspond toujours à
   * l'artiste suivi).
   * @param attractionId identifiant TM de l'attraction
   * @return nom actuel retourné par TM, ou null si introuvable/erreur réseau
   */
  public String recupererNomAttraction(final String attractionId) {
    return healthCheck.recupererNomAttraction(attractionId);
  }

  /**
   * Recherche des salles TM candidates par nom (redécouverte active, phase 1).
   *
   * @param nom nom de la salle recherchée
   * @return candidats trouvés (peut être vide)
   */
  public List<CandidatVenueTM> rechercherVenuesParNom(final String nom) {
    return healthCheck.rechercherVenuesParNom(nom);
  }

  /**
   * Compte les événements à venir d'une salle TM (validation d'un candidat de
   * redécouverte).
   *
   * @param venueId identifiant de salle TM
   * @return nombre d'événements trouvés, 0 en cas d'erreur
   */
  public int compterEventsVenue(final String venueId) {
    return healthCheck.compterEventsVenue(venueId);
  }

  /**
   * Récupère les concerts par région (géolocalisation centre de département).
   *
   * @param depts liste de codes département (ex: ["75", "92"])
   * @param classifs classifications TM à inclure ; par défaut music, opera,
   *     musical
   * @return liste des concerts trouvés pour tous les départements
   */
  @Override
  public List<ConcertBrut> fetchRegion(final List<String> depts, final List<String> classifs) {
    return fetchRegionCouverte(depts, classifs).concerts();
  }

  @Override
  public ResultatRegion fetchRegionCouverte(
      final List<String> depts, final List<String> classifs) {
    return TicketmasterRegionFetcher.fetchRegion(pageFetcher, depts, classifs);
  }

  @Override
  public LocalDate horizonSalle() {
    return FenetreTempsTM.derniereDateCouverte();
  }
}
