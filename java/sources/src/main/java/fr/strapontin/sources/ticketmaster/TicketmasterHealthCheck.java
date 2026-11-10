/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.ticketmaster;

import fr.strapontin.core.PositionLieu;
import fr.strapontin.core.CadenceurAppels;
import fr.strapontin.sources.http.UriSources;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.StatutIdTM;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Vérification de vivacité et redécouverte active des identifiants Ticketmaster
 * (salles et artistes) — extrait de {@link TicketmasterSource} pour séparer ce
 * concern du fetch principal.
 */
final class TicketmasterHealthCheck {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterHealthCheck.class);
  private static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";
  private static final String CLE_EMBEDDED = "_embedded";
  private static final String PARAM_APIKEY = "apikey";
  private static final String SUFFIXE_JSON = ".json";

  private final String apiKey;
  private final RestClient restClient;
  private final CadenceurAppels cadenceur;
  private final EtatFetchTM etatFetch;

  /* package-private : instancié uniquement par TicketmasterSource (même
   * package). */
  TicketmasterHealthCheck(
      final String apiKey,
      final RestClient restClient,
      final CadenceurAppels cadenceur,
      final EtatFetchTM etatFetch) {
    this.apiKey = apiKey;
    this.restClient = restClient;
    this.cadenceur = cadenceur;
    this.etatFetch = etatFetch;
  }

  private void noterQuotaEpuise(final Exception erreur) {
    if (EtatFetchTM.estQuotaEpuise(erreur)) {
      etatFetch.signalerQuotaEpuise();
    }
  }

  /* package-private : appelé par TicketmasterSource (même package). */
  StatutIdTM verifierVenue(final String venueId) {
    return verifierRessource("/venues/" + venueId + SUFFIXE_JSON);
  }

  /* package-private : appelé par TicketmasterSource (même package). */
  PositionLieu lirePositionVenue(final String venueId) {
    final URI uri = uriRessource("/venues/" + venueId);
    cadenceur.attendre();
    try {
      return ExtracteurVenueTM.extrairePositionTM(lireObjet(uri));
    } catch (Exception e) {
      noterQuotaEpuise(e);
      LOG.warn(
          "lirePositionVenue TM échoué venueId={} : {}",
          venueId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return PositionLieu.INCONNUE;
    }
  }

  /* package-private : appelé par TicketmasterSource (même package). */
  StatutIdTM verifierAttraction(final String attractionId) {
    return verifierRessource("/attractions/" + attractionId + SUFFIXE_JSON);
  }

  private StatutIdTM verifierRessource(final String chemin) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString(BASE_URL + chemin).queryParam(PARAM_APIKEY, apiKey));

    cadenceur.attendre();
    try {
      restClient.get().uri(uri).retrieve().toBodilessEntity();
      return StatutIdTM.VIVANT;
    } catch (HttpClientErrorException.NotFound e) {
      return StatutIdTM.MORT;
    } catch (Exception e) {
      noterQuotaEpuise(e);
      LOG.warn(
          "verifierRessource TM échoué chemin={} : {}",
          chemin,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return StatutIdTM.INDETERMINE;
    }
  }

  /* package-private : appelé par TicketmasterSource (même package). */
  String recupererNomAttraction(final String attractionId) {
    final URI uri = uriRessource("/attractions/" + attractionId);

    cadenceur.attendre();
    try {
      final Map<String, Object> data = lireObjet(uri);
      return data != null ? (String) data.get("name") : null;
    } catch (Exception e) {
      noterQuotaEpuise(e);
      LOG.warn(
          "recupererNomAttraction TM échoué attractionId={} : {}",
          attractionId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  /* package-private : appelé par TicketmasterSource (même package). */
  List<CandidatVenueTM> rechercherVenuesParNom(final String nom) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString(BASE_URL + "/venues.json")
                .queryParam("keyword", nom)
                .queryParam("countryCode", "FR")
                .queryParam(PARAM_APIKEY, apiKey));

    cadenceur.attendre();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return List.of();
      }
      final Map<String, Object> embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      final List<Map<String, Object>> venues =
          embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
      if (venues == null) {
        return List.of();
      }
      final List<CandidatVenueTM> resultat = new ArrayList<>();
      for (final Map<String, Object> v : venues) {
        final Object id = v.get("id");
        if (id != null) {
          resultat.add(new CandidatVenueTM((String) id, (String) v.get("name")));
        }
      }
      return resultat;
    } catch (Exception e) {
      noterQuotaEpuise(e);
      LOG.warn(
          "rechercherVenuesParNom TM échoué nom='{}' : {}",
          nom,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  /* package-private : appelé par TicketmasterSource (même package). */
  int compterEventsVenue(final String venueId) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString(BASE_URL + "/events.json")
                .queryParam("venueId", venueId)
                .queryParam("size", 1)
                .queryParam(PARAM_APIKEY, apiKey));

    cadenceur.attendre();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return 0;
      }
      final Map<String, Object> page = (Map<String, Object>) data.get("page");
      return page != null ? ((Number) page.get("totalElements")).intValue() : 0;
    } catch (Exception e) {
      noterQuotaEpuise(e);
      LOG.warn(
          "compterEventsVenue TM échoué venueId={} : {}",
          venueId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return 0;
    }
  }

  private URI uriRessource(final String chemin) {
    return UriSources.encoder(
        UriComponentsBuilder.fromUriString(BASE_URL + chemin + SUFFIXE_JSON)
            .queryParam(PARAM_APIKEY, apiKey));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> lireObjet(final URI uri) {
    return restClient.get().uri(uri).retrieve().body(Map.class);
  }
}
