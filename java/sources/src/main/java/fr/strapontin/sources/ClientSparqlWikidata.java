/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.UnknownContentTypeException;

/**
 * Client SPARQL Wikidata partagé entre {@link WikidataSalleSource} et {@link WikidataArtisteSource}
 * : User-Agent obligatoire, timeout 60s, retry 429 (Retry-After + backoff exponentiel) et erreurs
 * transitoires 502/503/content-type inattendu (×3).
 *
 * @since 2.6.0
 * @author Kwaite
 */
final class ClientSparqlWikidata {

  private static final Logger LOG = LoggerFactory.getLogger(ClientSparqlWikidata.class);

  static final String BASE_URL = "https://query.wikidata.org/sparql";
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  // 502 (nginx en amont de l'endpoint SPARQL) et un content-type inattendu (application/octet-
  // stream au lieu de application/sparql-results+json — constaté en production, réponse
  // vraisemblablement tronquée ou mal formée par un proxy intermédiaire) partagent le même
  // caractère transitoire que 503 : un simple retry les résout la plupart du temps.
  private static final int RETRY_TRANSITOIRE_MAX = 3;
  private static final int RETRY_TOTAL_MAX = 5;
  private static final Duration TIMEOUT_LECTURE = Duration.ofSeconds(60);
  // espacement proactif entre appels, sur le même principe que
  // OverpassSalleSource.DELAI_MIN_ENTRE_APPELS_MS — le retry réactif sur 429 (Retry-After 120s
  // constaté) a fait perdre ~4 des 5 minutes d'un cycle nocturne. Aucune politique de rate-limit
  // publiée par Wikidata pour l'endpoint SPARQL ; valeur calibrée empiriquement, sans contrainte
  // de temps réel (cycle nocturne unique).
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 2000;

  private final RestClient restClient;
  private final fr.strapontin.core.CompteurBilanFetch compteurBilan =
      new fr.strapontin.core.CompteurBilanFetch();
  private final CadenceurAppels cadenceur = new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS);

  /** Crée un client avec un RestClient par défaut (timeout connexion 5s, lecture 60s). */
  ClientSparqlWikidata() {
    this(construireRestClientParDefaut());
  }

  /**
   * Crée un client avec un RestClient personnalisé (injection de test).
   *
   * @param restClient client HTTP à utiliser
   */
  ClientSparqlWikidata(final RestClient restClient) {
    this.restClient = restClient;
  }

  private static RestClient construireRestClientParDefaut() {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(TIMEOUT_LECTURE);
    return RestClient.builder().requestFactory(factory).build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> effectuerRequete(final String sparql) {
    return restClient
        .post()
        .uri(BASE_URL)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/sparql-results+json")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8))
        .retrieve()
        .body(Map.class);
  }

  /**
   * Exécute une requête SPARQL avec retry sur 429 (Retry-After + backoff exponentiel) et 503 (×3).
   * Retourne {@code null} si toutes les tentatives échouent — l'appelant traite cela comme une
   * absence de résultat, sans lever d'exception.
   *
   * @param sparql corps de la requête SPARQL
   * @return corps JSON désérialisé, ou null en cas d'échec définitif
   */
  Map<String, Object> executer(final String sparql) {
    // un appel logique = un executer, quel que soit le nombre de retries internes —
    // seul un échec définitif (retries épuisés) compte comme erreur pour le bilan de panne.
    compteurBilan.enregistrerAppel();
    cadenceur.attendre();
    int tentative = 0;
    int tentativesTransitoires = 0;
    while (tentative < RETRY_TOTAL_MAX) {
      tentative++;
      try {
        return effectuerRequete(sparql);
      } catch (HttpClientErrorException.TooManyRequests e) {
        final long attenteMs =
            extraireRetryAfterMs(e.getResponseHeaders()).orElse(1000L << tentative);
        LOG.warn("Wikidata 429 — attente {} ms avant retry", attenteMs);
        dormir(attenteMs);
      } catch (HttpServerErrorException.ServiceUnavailable
          | HttpServerErrorException.BadGateway
          | UnknownContentTypeException e) {
        tentativesTransitoires++;
        if (tentativesTransitoires >= RETRY_TRANSITOIRE_MAX) {
          LOG.warn(
              "Wikidata erreur transitoire après {} tentatives, abandon : {}",
              RETRY_TRANSITOIRE_MAX,
              e.getMessage());
          compteurBilan.enregistrerErreur();
          return null;
        }
        dormir(1000L * tentativesTransitoires);
      } catch (Exception e) {
        compteurBilan.enregistrerErreur();
        LOG.warn("Wikidata SPARQL erreur : {}", e.getMessage());
        return null;
      }
    }
    compteurBilan.enregistrerErreur();
    LOG.warn("Wikidata SPARQL abandon après {} tentatives", RETRY_TOTAL_MAX);
    return null;
  }

  /**
   * Retourne le bilan agrégé des appels SPARQL Wikidata depuis le dernier prélèvement et remet les
   * compteurs à zéro — client partagé entre {@link WikidataSalleSource} et {@link
   * WikidataArtisteSource}, mais une instance par source (non partagée) : chacune prélève son
   * propre bilan sans interférer avec l'autre.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  fr.strapontin.core.BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  private static Optional<Long> extraireRetryAfterMs(final HttpHeaders headers) {
    if (headers == null) {
      return Optional.empty();
    }
    final String valeur = headers.getFirst("Retry-After");
    if (valeur == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(valeur) * 1000);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static void dormir(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Extrait la valeur d'une variable SPARQL dans un binding de résultat.
   *
   * @param binding binding JSON d'une ligne de résultat
   * @param cle nom de la variable SPARQL
   * @return valeur textuelle, ou null si absente
   */
  @SuppressWarnings("unchecked")
  static String valeurBinding(final Map<String, Object> binding, final String cle) {
    final Map<String, Object> v = (Map<String, Object>) binding.get(cle);
    return v != null ? (String) v.get("value") : null;
  }

  /**
   * Extrait la liste des bindings d'une réponse SPARQL JSON — partagé entre {@link
   * WikidataSalleSource} et {@link WikidataArtisteSource}, jusqu'ici dupliqué à l'identique dans
   * les deux classes.
   *
   * @param data réponse JSON désérialisée d'une requête SPARQL
   * @return la liste des bindings, vide si absente
   */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> extraireBindings(final Map<String, Object> data) {
    final Map<String, Object> results = (Map<String, Object>) data.get("results");
    if (results == null) {
      return List.of();
    }
    final List<Map<String, Object>> bindings = (List<Map<String, Object>>) results.get("bindings");
    return bindings != null ? bindings : List.of();
  }

  /**
   * Échappe une chaîne pour insertion sûre dans un littéral SPARQL délimité par des guillemets
   * doubles (ex. {@code mwapi:search "..."}). L'antislash est échappé avant le guillemet, sinon le
   * {@code \"} produit par l'échappement du guillemet serait lui-même ré-échappé.
   *
   * @param s la chaîne à insérer dans le littéral
   * @return la chaîne échappée
   */
  static String echapperLitteral(final String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static final Pattern MOTIF_Q_ID = Pattern.compile("^Q[0-9]+$");

  /**
   * Vérifie qu'une chaîne est un Q-ID Wikidata valide (ex. {@code Q3438008}), avant de la
   * concaténer nue (sans guillemets) dans une clause {@code VALUES ?item { wd:... }} —
   * contrairement à {@link #echapperLitteral}, qui ne protège que les littéraux entre guillemets.
   *
   * @param qId identifiant à vérifier
   * @return {@code true} si le format est un Q-ID valide
   */
  static boolean estQIdValide(final String qId) {
    return qId != null && MOTIF_Q_ID.matcher(qId).matches();
  }

  // Wikidata renvoie P625 au format WKT "Point(lon lat)" (ordre longitude puis latitude, jamais
  // l'inverse — convention géographique standard).
  private static final Pattern COORDONNEES_WKT =
      Pattern.compile("Point\\(([-\\d.]+)\\s+([-\\d.]+)\\)");

  /**
   * Parse une valeur WKT Wikidata en {@code [longitude, latitude]} — partagé entre {@link
   * WikidataSalleSource} et {@link WikidataSalleDecouverte}.
   *
   * @param wkt valeur brute du binding {@code ?coord}, ou {@code null}
   * @return {@code [longitude, latitude]}, ou {@code null} si absent/mal formé
   */
  static double[] parserCoordonnees(final String wkt) {
    if (wkt == null) {
      return null;
    }
    final Matcher matcher = COORDONNEES_WKT.matcher(wkt);
    if (!matcher.find()) {
      return null;
    }
    try {
      return new double[] {
        Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))
      };
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
