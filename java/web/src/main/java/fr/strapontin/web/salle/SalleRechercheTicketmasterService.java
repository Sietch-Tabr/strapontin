/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.web.WorkerTriggerUtils;
import fr.strapontin.web.config.ConfigService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Recherche de salles par nom via l'API Ticketmaster (recherche interactive à l'ajout, module web).
 *
 * <p>Extrait de {@link SalleService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class SalleRechercheTicketmasterService {

  private static final Logger LOG =
      LoggerFactory.getLogger(SalleRechercheTicketmasterService.class);
  private static final String TM_VENUES_URL =
      "https://app.ticketmaster.com/discovery/v2/venues.json";

  private final ConfigService configService;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final CadenceurAppels cadenceurTicketmaster;

  /**
   * @param configService lecture de la clé API Ticketmaster
   * @param restClient client HTTP partagé
   * @param objectMapper désérialiseur JSON
   * @param cadenceurTicketmaster cadenceur partagé inter-processus avec le worker
   */
  SalleRechercheTicketmasterService(
      final ConfigService configService,
      final RestClient restClient,
      final ObjectMapper objectMapper,
      final CadenceurAppels cadenceurTicketmaster) {
    this.configService = configService;
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.cadenceurTicketmaster = cadenceurTicketmaster;
  }

  /**
   * @param terme terme de recherche saisi par l'utilisateur
   * @return suggestions Ticketmaster, vide si aucune, si non configuré, ou en cas d'échec réseau
   */
  List<ResultatSalle> rechercher(final String terme) {
    final Optional<String> tmKey = configService.lireConfig("TICKETMASTER_API_KEY");
    if (tmKey.isEmpty()) {
      return List.of();
    }

    try {
      final WorkerTriggerUtils.ReponseHttp reponse = appelerTicketmasterVenues(terme, tmKey.get());
      if (reponse.statut() != 200) {
        return List.of();
      }

      final List<Map<String, Object>> venues = extraireVenuesTM(reponse.corps());
      if (venues == null) {
        return List.of();
      }

      return venues.stream()
          .map(this::mapperVenueTM)
          .filter(r -> r.salle() != null)
          .collect(Collectors.toList());

    } catch (Exception e) {
      LOG.warn("TM recherche salle échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  private WorkerTriggerUtils.ReponseHttp appelerTicketmasterVenues(
      final String terme, final String tmKey) {
    final String url =
        TM_VENUES_URL
            + "?keyword="
            + URLEncoder.encode(terme, StandardCharsets.UTF_8)
            + "&countryCode=FR&size=6&apikey="
            + URLEncoder.encode(tmKey, StandardCharsets.UTF_8);

    cadenceurTicketmaster.attendre();
    return restClient
        .get()
        .uri(url)
        .exchange(
            (_, resp) ->
                new WorkerTriggerUtils.ReponseHttp(
                    resp.getStatusCode().value(), resp.bodyTo(String.class)));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extraireVenuesTM(final String corps)
      throws JsonProcessingException {
    final Map<String, Object> root = objectMapper.readValue(corps, Map.class);
    final Map<String, Object> embedded = (Map<String, Object>) root.get("_embedded");
    return embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
  }

  @SuppressWarnings("unchecked")
  private ResultatSalle mapperVenueTM(final Map<String, Object> v) {
    final String nomBrut = (String) v.get("name");
    if (nomBrut == null) {
      return new ResultatSalle(null, null, null, null, "ticketmaster");
    }
    final String nom = Normalisation.normaliserCasse(nomBrut);
    if (nomBrut.contains("??")) {
      LOG.warn("TM salle nom corrompu id={} nom='{}'", v.get("id"), nomBrut);
    }
    final String id = (String) v.get("id");
    final Map<String, Object> city = (Map<String, Object>) v.get("city");
    final String ville = city != null ? (String) city.get("name") : null;
    return new ResultatSalle(nom, ville, id, null, "ticketmaster");
  }
}
