/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.web.BudgetRecherchesExternes;
import fr.strapontin.web.CadenceurAppelsPartage;
import fr.strapontin.web.WorkerTriggerUtils;
import fr.strapontin.web.config.ConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Recherche de salles par nom via Nominatim (OSM), sans clé API — extrait de
 * {@link SalleService} (limite 500 lignes).
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class SalleRechercheNominatimService {

  private static final Logger LOG = LoggerFactory.getLogger(SalleRechercheNominatimService.class);
  private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
  // Types Nominatim pertinents pour une salle de spectacle — testés sur le type
  // de premier niveau ET sur extratags.amenity (une salle taguée
  // leisure/tourism, ex. un stade, peut porter amenity en tag secondaire).
  // community_centre couvre les salles des fêtes/polyvalentes (petites
  // communes).
  private static final Set<String> TYPES_SALLE_NOMINATIM =
      Set.of(
          "music_venue",
          "arts_centre",
          "theatre",
          "nightclub",
          "events_venue",
          "concert_hall",
          "community_centre");

  private final ConfigService configService;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final BudgetRecherchesExternes budgetRecherches;
  private final CadenceurAppels cadenceur;

  /**
   * @param configService lecture de l'email de contact (User-Agent Nominatim)
   * @param restClient client HTTP partagé
   * @param objectMapper désérialiseur JSON
   * @param budgetRecherches plafond global des recherches vers Nominatim
   * @param jdbc base partagée avec le worker, qui coordonne la seconde entre les
   *     deux processus
   */
  /* package-private : instancié uniquement par SalleService (même package). */
  @Autowired
  SalleRechercheNominatimService(
      final ConfigService configService,
      final RestClient restClient,
      final ObjectMapper objectMapper,
      final BudgetRecherchesExternes budgetRecherches,
      final NamedParameterJdbcTemplate jdbc) {
    this(
        configService,
        restClient,
        objectMapper,
        budgetRecherches,
        new CadenceurAppelsPartage(jdbc, "nominatim", ClientNominatim.DELAI_MIN_ENTRE_APPELS_MS));
  }

  /* package-private : injection d'un cadenceur de test. */
  SalleRechercheNominatimService(
      final ConfigService configService,
      final RestClient restClient,
      final ObjectMapper objectMapper,
      final BudgetRecherchesExternes budgetRecherches,
      final CadenceurAppels cadenceur) {
    this.configService = configService;
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.budgetRecherches = budgetRecherches;
    this.cadenceur = cadenceur;
  }

  /**
   * Recherche des salles par nom via Nominatim (OSM), sans clé API — couvre
   * les salles hors référentiel Ticketmaster. User-Agent obligatoire selon la
   * politique d'usage Nominatim.
   *
   * @param terme terme de recherche saisi par l'utilisateur
   * @return suggestions filtrées par type, vide si aucune ou échec réseau
   */
  /* package-private : appelé par SalleService (même package). */
  List<ResultatSalle> rechercher(final String terme) {
    if (!budgetRecherches.consommer(BudgetRecherchesExternes.Service.NOMINATIM)) {
      return List.of();
    }
    try {
      cadenceur.attendre();
      final String contact =
          configService.lireConfig("CONTACT_EMAIL").orElse("contact@strapontin.fr");
      final String gabarit =
          NOMINATIM_URL
              + "?q={terme}&format=json&limit=6&extratags=1&addressdetails=1&countrycodes=fr";
      final WorkerTriggerUtils.ReponseHttp reponse =
          restClient
              .get()
              .uri(gabarit, terme)
              .header("User-Agent", "Strapontin/2.6.3 (contact: " + contact + ")")
              .exchange(
                  (_, resp) ->
                      new WorkerTriggerUtils.ReponseHttp(
                          resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != HttpStatus.OK.value()) {
        LOG.warn("Nominatim erreur HTTP {} pour '{}'", reponse.statut(), terme);
        return List.of();
      }
      return parseNominatimResultats(reponse.corps());
    } catch (Exception e) {
      LOG.warn("Nominatim recherche salle échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  /**
   * Filtre/convertit les résultats bruts Nominatim (critère de pertinence :
   * détail CLAUDE.md, section Sources de données) ; package-private pour
   * être testable sans appel réseau réel.
   * @param json corps de la réponse Nominatim (tableau JSON)
   * @return suggestions filtrées, vide si aucune ou échec de parsing
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par SalleService (même package). */
  List<ResultatSalle> parseNominatimResultats(final String json) {
    final List<ResultatSalle> resultats = new ArrayList<>();
    try {
      final List<Map<String, Object>> items = objectMapper.readValue(json, List.class);
      for (final Map<String, Object> item : items) {
        final ResultatSalle resultat = parseNominatimItem(item);
        if (resultat != null) {
          resultats.add(resultat);
        }
      }
    } catch (Exception e) {
      LOG.warn("Nominatim parsing JSON échoué", e);
    }
    return resultats;
  }

  @SuppressWarnings("unchecked")
  private ResultatSalle parseNominatimItem(final Map<String, Object> item) {
    final String type = (String) item.get("type");
    final Map<String, Object> extratags = (Map<String, Object>) item.get("extratags");
    final String amenitySecondaire = extratags != null ? (String) extratags.get("amenity") : null;
    final boolean pertinent =
        TYPES_SALLE_NOMINATIM.contains(type)
            || (amenitySecondaire != null && TYPES_SALLE_NOMINATIM.contains(amenitySecondaire));
    if (!pertinent) {
      return null;
    }

    final String displayName = (String) item.get("display_name");
    if (displayName == null || displayName.isBlank()) {
      return null;
    }
    final String nom = displayName.split(",")[0].strip();

    final String osmType = (String) item.get("osm_type");
    final Object osmIdBrut = item.get("osm_id");
    final String osmId = osmType != null && osmIdBrut != null ? osmType + "/" + osmIdBrut : null;

    final Map<String, Object> address = (Map<String, Object>) item.get("address");
    final String ville = extraireVille(address);

    final String wikidataId = extratags != null ? (String) extratags.get("wikidata") : null;

    return new ResultatSalle(nom, ville, null, "osm", osmId, wikidataId);
  }

  private static String extraireVille(final Map<String, Object> address) {
    if (address == null) {
      return null;
    }
    for (final String cle : List.of("city", "town", "village", "municipality")) {
      final Object valeur = address.get(cle);
      if (valeur instanceof String chaine && !chaine.isBlank()) {
        return chaine;
      }
    }
    return null;
  }
}
