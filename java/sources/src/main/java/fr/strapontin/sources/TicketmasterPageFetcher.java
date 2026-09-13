/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.QuotaTM;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pagination bas niveau des appels Ticketmaster Discovery v2 (une page,
 * ou toutes les pages bornées à {@code NB_PAGES_MAX}) — extrait
 * d'{@link TicketmasterSource}.
 * @since 2.9.1
 * @author Kwaite
 */
final class TicketmasterPageFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterPageFetcher.class);
  private static final String CLE_EMBEDDED = "_embedded";
  // TM refuse offset > 1000 : avec size=200, page 6 → offset 1000 (ok), page 7
  // → 1200 (HTTP 400)
  private static final int NB_PAGES_MAX = 6;

  private final RestClient restClient;
  private final String apiKey;
  private final CadenceurAppels cadenceur;
  private final EtatFetchTM etatFetch;
  private final int taillePage;

  /**
   * @param restClient client HTTP Ticketmaster partagé
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   * @param etatFetch état partagé (quota, concerts manqués, bilan)
   * @param taillePage taille de page (calcul du nombre de pages total
   *     dans le log de plafonnement)
   */
  /* package-private : instancié uniquement par TicketmasterSource (même
   * package). */
  TicketmasterPageFetcher(
      final RestClient restClient,
      final String apiKey,
      final CadenceurAppels cadenceur,
      final EtatFetchTM etatFetch,
      final int taillePage) {
    this.restClient = restClient;
    this.apiKey = apiKey;
    this.cadenceur = cadenceur;
    this.etatFetch = etatFetch;
    this.taillePage = taillePage;
  }

  /**
   * @param succes {@code false} si l'appel HTTP a échoué — distingue une page
   *     réellement vide (succès, 0 résultat) d'un appel qui n'a jamais abouti,
   *     faute de quoi les deux se traduisent identiquement par
   *     {@code concerts} vide et {@code total=0} .
   */
  /* package-private : type de retour interne, utilisé par TicketmasterSource
   * (même package). */
  record ResultatPage(
      List<ConcertBrut> concerts, int total, int totalPages, QuotaTM quota, boolean succes) {}

  /**
   * @param succes {@code false} si au moins une page a échoué (voir
   *     {@link ResultatPage#succes} ).
   */
  /* package-private : type de retour interne, utilisé par TicketmasterSource
   * (même package). */
  record ResultatMultiPage(List<ConcertBrut> concerts, boolean succes) {}

  /* package-private : appelé par TicketmasterSource (même package). */
  ResultatPage fetchPage(
      final UriComponentsBuilder uriBase, final int numPage, final String artisteFallback) {
    cadenceur.attendre();
    final String uri = construireUriFetchPage(uriBase, numPage);

    etatFetch.enregistrerAppel();
    final Instant avantAppelHttp = Instant.now();
    try {
      final ResponseEntity<Map> response = restClient.get().uri(uri).retrieve().toEntity(Map.class);
      final QuotaTM quota = extraireQuotaTm(response.getHeaders());
      return construireResultatPage(response.getBody(), quota, artisteFallback);
    } catch (Exception e) {
      final String uriSafe = uri.replaceAll("apikey=[^&]+", "apikey=***");
      final long dureeAppelMs = Duration.between(avantAppelHttp, Instant.now()).toMillis();
      // Diagnostic dédié au 429 (Spike Arrest) : la durée réelle de l'appel
      // HTTP objective si le rejet survient après une latence réseau anormale
      // (jitter) plutôt qu'immédiatement (autre cause) — le cadenceur n'espace
      // que l'instant de départ côté client, jamais l'instant d'arrivée
      // effective côté serveur TM.
      if (e instanceof HttpClientErrorException httpEx && httpEx.getStatusCode().value() == 429) {
        LOG.warn(
            "Ticketmaster 429 (Spike Arrest) url={} dureeAppelHttpMs={}", uriSafe, dureeAppelMs);
      } else {
        LOG.warn(
            "erreur HTTP Ticketmaster url={} : {}",
            uriSafe,
            TicketmasterSource.redigerApiKey(e.getMessage()));
      }
      etatFetch.enregistrerErreur();
      return new ResultatPage(List.of(), 0, NB_PAGES_MAX, null, false);
    }
  }

  private String construireUriFetchPage(final UriComponentsBuilder uriBase, final int numPage) {
    return uriBase
        .cloneBuilder()
        .queryParam("apikey", apiKey)
        .queryParam("locale", "fr,en,*")
        .queryParam("countryCode", "FR")
        .queryParam("includeTBA", "no")
        .queryParam("includeTBD", "no")
        .queryParam("page", numPage)
        .build()
        .toUriString();
  }

  private static QuotaTM extraireQuotaTm(final HttpHeaders headers) {
    if (headers.get("Rate-Limit-Available") == null) {
      return null;
    }
    return new QuotaTM(
        Integer.parseInt(headers.getFirst("Rate-Limit-Available")),
        Integer.parseInt(headers.getOrEmpty("Rate-Limit").stream().findFirst().orElse("0")),
        Long.parseLong(headers.getOrEmpty("Rate-Limit-Reset").stream().findFirst().orElse("0")),
        Instant.now().toString());
  }

  @SuppressWarnings("unchecked")
  private ResultatPage construireResultatPage(
      final Map data, final QuotaTM quota, final String artisteFallback) {
    if (data == null) {
      return new ResultatPage(List.of(), 0, NB_PAGES_MAX, quota, true);
    }

    final Map<String, Object> page = (Map<String, Object>) data.get("page");
    final int total = page != null ? ((Number) page.get("totalElements")).intValue() : 0;
    final int totalPages =
        page != null ? ((Number) page.get("totalPages")).intValue() : NB_PAGES_MAX;

    final Map<String, Object> embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
    final List<Map<String, Object>> evts =
        embedded != null ? (List<Map<String, Object>>) embedded.get("events") : null;
    if (evts == null) {
      return new ResultatPage(List.of(), total, totalPages, quota, true);
    }

    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final Map<String, Object> evt : evts) {
      final ConcertBrut concert = TicketmasterSource.mapEvenement(evt, artisteFallback);
      if (concert != null) {
        concerts.add(concert);
      }
    }
    return new ResultatPage(concerts, total, totalPages, quota, true);
  }

  /**
   * Récupère toutes les pages disponibles (bornées à NB_PAGES_MAX).
   * @param uriBase URI de base de la requête, sans pagination
   * @param artisteFallback nom recherché (fetch:artiste), null sinon
   * @param contexte identifiant du centre de recherche (ex. "dept=75"),
   *     inclus dans le log de plafonnement
   * @return concerts récupérés sur toutes les pages, succès si complet
   */
  /* package-private : appelé par TicketmasterSource (même package). */
  ResultatMultiPage fetchToutesPages(
      final UriComponentsBuilder uriBase, final String artisteFallback, final String contexte) {
    final List<ConcertBrut> tousLesEvents = new ArrayList<>();
    int borneMax = NB_PAGES_MAX;
    int totalElements = 0;
    boolean continuer = true;
    boolean succes = true;

    for (int numPage = 0; numPage < borneMax && continuer; numPage++) {
      final ResultatPage resultat = fetchPage(uriBase, numPage, artisteFallback);
      if (!resultat.succes()) {
        succes = false;
      }
      if (resultat.quota() != null) {
        etatFetch.mettreAJourQuota(resultat.quota());
      }
      if (numPage == 0) {
        totalElements = resultat.total();
        borneMax = Math.min(resultat.totalPages(), NB_PAGES_MAX);
      }
      tousLesEvents.addAll(resultat.concerts());

      final boolean fini =
          resultat.concerts().isEmpty()
              || (totalElements > 0 && tousLesEvents.size() >= totalElements);
      continuer = !fini;
    }

    final int nbPagesTotal = (int) Math.ceil((double) totalElements / taillePage);
    if (nbPagesTotal > NB_PAGES_MAX) {
      etatFetch.ajouterConcertsManques(Math.max(0, totalElements - tousLesEvents.size()));
      LOG.warn(
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
}
