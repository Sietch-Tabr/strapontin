/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.sources.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Vérification de vivacité et redécouverte active des identifiants Ticketmaster (salles et
 * artistes) — extrait de {@link TicketmasterSource} pour séparer ce concern du fetch principal.
 */
final class TicketmasterHealthCheck {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterHealthCheck.class);
  private static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";
  private static final String CLE_EMBEDDED = "_embedded";
  private static final String PARAM_APIKEY = "apikey";

  private final String apiKey;
  private final RestClient restClient;
  private final CadenceurAppels cadenceur;

  TicketmasterHealthCheck(
      final String apiKey, final RestClient restClient, final CadenceurAppels cadenceur) {
    this.apiKey = apiKey;
    this.restClient = restClient;
    this.cadenceur = cadenceur;
  }

  StatutIdTM verifierVenue(final String venueId) {
    return verifierRessource("/venues/" + venueId + ".json");
  }

  StatutIdTM verifierAttraction(final String attractionId) {
    return verifierRessource("/attractions/" + attractionId + ".json");
  }

  private StatutIdTM verifierRessource(final String chemin) {
    final String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + chemin)
            .queryParam(PARAM_APIKEY, apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      restClient.get().uri(uri).retrieve().toBodilessEntity();
      return StatutIdTM.VIVANT;
    } catch (HttpClientErrorException.NotFound e) {
      return StatutIdTM.MORT;
    } catch (Exception e) {
      LOG.warn(
          "verifierRessource TM échoué chemin={} : {}",
          chemin,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return StatutIdTM.VIVANT;
    }
  }

  @SuppressWarnings("unchecked")
  String recupererNomAttraction(final String attractionId) {
    final String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/attractions/" + attractionId + ".json")
            .queryParam(PARAM_APIKEY, apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return data != null ? (String) data.get("name") : null;
    } catch (Exception e) {
      LOG.warn(
          "recupererNomAttraction TM échoué attractionId={} : {}",
          attractionId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  List<CandidatVenueTM> rechercherVenuesParNom(final String nom) {
    final String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/venues.json")
            .queryParam("keyword", nom)
            .queryParam("countryCode", "FR")
            .queryParam(PARAM_APIKEY, apiKey)
            .build()
            .toUriString();
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
      LOG.warn(
          "rechercherVenuesParNom TM échoué nom='{}' : {}",
          nom,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  int compterEventsVenue(final String venueId) {
    final String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/events.json")
            .queryParam("venueId", venueId)
            .queryParam("size", 1)
            .queryParam(PARAM_APIKEY, apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return 0;
      }
      final Map<String, Object> page = (Map<String, Object>) data.get("page");
      return page != null ? ((Number) page.get("totalElements")).intValue() : 0;
    } catch (Exception e) {
      LOG.warn(
          "compterEventsVenue TM échoué venueId={} : {}",
          venueId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return 0;
    }
  }
}
