/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/// Client SPARQL Wikidata partagé entre {@link WikidataSalleSource} et
/// {@link WikidataArtisteSource} : User-Agent obligatoire, timeout 60s, retry 429 (Retry-After +
/// backoff exponentiel) et 503 (×3).
///
/// @since 2.6.0
/// @author Kwaite
final class ClientSparqlWikidata {

  private static final Logger log = LoggerFactory.getLogger(ClientSparqlWikidata.class);

  static final String BASE_URL = "https://query.wikidata.org/sparql";
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  private static final int RETRY_503_MAX = 3;
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
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// Crée un client avec un RestClient par défaut (timeout connexion 5s, lecture 60s).
  ClientSparqlWikidata() {
    this(construireRestClientParDefaut());
  }

  /// Crée un client avec un RestClient personnalisé (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  ClientSparqlWikidata(RestClient restClient) {
    this.restClient = restClient;
  }

  private static RestClient construireRestClientParDefaut() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(TIMEOUT_LECTURE);
    return RestClient.builder().requestFactory(factory).build();
  }

  @SuppressWarnings("unchecked")
  private java.util.Map<String, Object> effectuerRequete(String sparql) {
    return restClient
        .post()
        .uri(BASE_URL)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/sparql-results+json")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8))
        .retrieve()
        .body(java.util.Map.class);
  }

  /// Exécute une requête SPARQL avec retry sur 429 (Retry-After + backoff exponentiel) et 503
  /// (×3). Retourne `null` si toutes les tentatives échouent — l'appelant traite cela comme une
  /// absence de résultat, sans lever d'exception.
  ///
  /// @param sparql corps de la requête SPARQL
  /// @return corps JSON désérialisé, ou null en cas d'échec définitif
  java.util.Map<String, Object> executer(String sparql) {
    // un appel logique = un executer, quel que soit le nombre de retries internes —
    // seul un échec définitif (retries épuisés) compte comme erreur pour le bilan de panne.
    compteurBilan.enregistrerAppel();
    attendreRateLimitGlobal();
    int tentative = 0;
    int tentatives503 = 0;
    while (tentative < RETRY_TOTAL_MAX) {
      tentative++;
      try {
        return effectuerRequete(sparql);
      } catch (HttpClientErrorException.TooManyRequests e) {
        long attenteMs = extraireRetryAfterMs(e.getResponseHeaders()).orElse(1000L << tentative);
        log.warn("Wikidata 429 — attente {} ms avant retry", attenteMs);
        dormir(attenteMs);
      } catch (HttpServerErrorException.ServiceUnavailable e) {
        tentatives503++;
        if (tentatives503 >= RETRY_503_MAX) {
          log.warn("Wikidata 503 après {} tentatives, abandon", RETRY_503_MAX);
          compteurBilan.enregistrerErreur();
          return null;
        }
        dormir(1000L * tentatives503);
      } catch (Exception e) {
        compteurBilan.enregistrerErreur();
        log.warn("Wikidata SPARQL erreur : {}", e.getMessage());
        return null;
      }
    }
    compteurBilan.enregistrerErreur();
    log.warn("Wikidata SPARQL abandon après {} tentatives", RETRY_TOTAL_MAX);
    return null;
  }

  /// Retourne le bilan agrégé des appels SPARQL Wikidata depuis le dernier prélèvement et
  /// remet les compteurs à zéro — client partagé entre {@link WikidataSalleSource} et
  /// {@link WikidataArtisteSource}, mais une instance par source (non partagée) : chacune
  /// prélève son propre bilan sans interférer avec l'autre.
  ///
  /// @return le bilan depuis le dernier prélèvement
  fr.strapontin.core.BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  private static Optional<Long> extraireRetryAfterMs(HttpHeaders headers) {
    if (headers == null) return Optional.empty();
    String valeur = headers.getFirst("Retry-After");
    if (valeur == null) return Optional.empty();
    try {
      return Optional.of(Long.parseLong(valeur) * 1000);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static void dormir(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// Espace proactivement les appels SPARQL Wikidata, sur le même principe que
  /// {@code OverpassSalleSource.attendreRateLimitGlobal}.
  private synchronized void attendreRateLimitGlobal() {
    long maintenant = System.currentTimeMillis();
    long prochainAppel = dernierAppelMs.get() + DELAI_MIN_ENTRE_APPELS_MS;
    if (maintenant < prochainAppel) {
      dormir(prochainAppel - maintenant);
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }

  /// Extrait la valeur d'une variable SPARQL dans un binding de résultat.
  ///
  /// @param binding binding JSON d'une ligne de résultat
  /// @param cle     nom de la variable SPARQL
  /// @return valeur textuelle, ou null si absente
  @SuppressWarnings("unchecked")
  static String valeurBinding(java.util.Map<String, Object> binding, String cle) {
    var v = (java.util.Map<String, Object>) binding.get(cle);
    return v != null ? (String) v.get("value") : null;
  }
}
