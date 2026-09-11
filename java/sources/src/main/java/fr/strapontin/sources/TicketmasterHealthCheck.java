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

  private static final Logger log = LoggerFactory.getLogger(TicketmasterHealthCheck.class);
  private static final String BASE_URL = "https://app.ticketmaster.com/discovery/v2";
  private static final String CLE_EMBEDDED = "_embedded";

  private final String apiKey;
  private final RestClient restClient;
  private final CadenceurAppels cadenceur;

  TicketmasterHealthCheck(String apiKey, RestClient restClient, CadenceurAppels cadenceur) {
    this.apiKey = apiKey;
    this.restClient = restClient;
    this.cadenceur = cadenceur;
  }

  StatutIdTM verifierVenue(String venueId) {
    return verifierRessource("/venues/" + venueId + ".json");
  }

  StatutIdTM verifierAttraction(String attractionId) {
    return verifierRessource("/attractions/" + attractionId + ".json");
  }

  private StatutIdTM verifierRessource(String chemin) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + chemin)
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      restClient.get().uri(uri).retrieve().toBodilessEntity();
      return StatutIdTM.VIVANT;
    } catch (HttpClientErrorException.NotFound e) {
      return StatutIdTM.MORT;
    } catch (Exception e) {
      log.warn(
          "verifierRessource TM échoué chemin={} : {}",
          chemin,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return StatutIdTM.VIVANT;
    }
  }

  @SuppressWarnings("unchecked")
  String recupererNomAttraction(String attractionId) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/attractions/" + attractionId + ".json")
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      return data != null ? (String) data.get("name") : null;
    } catch (Exception e) {
      log.warn(
          "recupererNomAttraction TM échoué attractionId={} : {}",
          attractionId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  List<CandidatVenueTM> rechercherVenuesParNom(String nom) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/venues.json")
            .queryParam("keyword", nom)
            .queryParam("countryCode", "FR")
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return List.of();
      var embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      var venues = embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
      if (venues == null) return List.of();
      List<CandidatVenueTM> resultat = new ArrayList<>();
      for (var v : venues) {
        Object id = v.get("id");
        if (id != null) resultat.add(new CandidatVenueTM((String) id, (String) v.get("name")));
      }
      return resultat;
    } catch (Exception e) {
      log.warn(
          "rechercherVenuesParNom TM échoué nom='{}' : {}",
          nom,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  int compterEventsVenue(String venueId) {
    String uri =
        UriComponentsBuilder.fromUriString(BASE_URL + "/events.json")
            .queryParam("venueId", venueId)
            .queryParam("size", 1)
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
    cadenceur.attendre();
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return 0;
      var page = (Map<String, Object>) data.get("page");
      return page != null ? ((Number) page.get("totalElements")).intValue() : 0;
    } catch (Exception e) {
      log.warn(
          "compterEventsVenue TM échoué venueId={} : {}",
          venueId,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return 0;
    }
  }
}
