/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.ticketmaster;

import fr.strapontin.core.CadenceurAppels;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.http.UriSources;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Résolution de l'attractionId Ticketmaster correspondant à un nom
 * d'artiste, via l'API attractions — extrait de {@link
 * TicketmasterSource}.
 * @since 2.9.1
 * @author Kwaite
 */
final class TicketmasterAttractionResolver {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterAttractionResolver.class);
  private static final String CLE_EMBEDDED = "_embedded";

  private final RestClient restClient;
  private final String apiKey;
  private final String baseUrl;
  private final CadenceurAppels cadenceur;

  /**
   * @param restClient client HTTP Ticketmaster partagé
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param baseUrl URL de base de l'API Ticketmaster Discovery v2
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   */
  /* package-private : instancié uniquement par TicketmasterSource (même
   * package). */
  TicketmasterAttractionResolver(
      final RestClient restClient,
      final String apiKey,
      final String baseUrl,
      final CadenceurAppels cadenceur) {
    this.restClient = restClient;
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.cadenceur = cadenceur;
  }

  /**
   * Recherche l'attractionId TM pour un artiste par son nom — parcourt
   * jusqu'à 10 résultats pour la première correspondance exacte normalisée
   * (TM peut trier par popularité, ex. "Shy" avant "Shy'm").
   * @param nom nom de l'artiste
   * @return attractionId TM, ou null si aucun résultat exact des 10 premiers
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par TicketmasterSource (même package). */
  String rechercherAttractionId(final String nom) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString(baseUrl + "/attractions.json")
                .queryParam("keyword", nom)
                .queryParam("locale", "*")
                .queryParam("size", 10)
                .queryParam("apikey", apiKey));

    cadenceur.attendre();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return null;
      }
      final Map<String, Object> embedded = (Map<String, Object>) data.get(CLE_EMBEDDED);
      final List<Map<String, Object>> attractions =
          embedded != null ? (List<Map<String, Object>>) embedded.get("attractions") : null;
      if (attractions == null || attractions.isEmpty()) {
        return null;
      }
      final String nomNorm = Normalisation.normaliserTexte(nom);
      for (final Map<String, Object> attraction : attractions) {
        final String nomAttraction = (String) attraction.getOrDefault("name", "");
        if (Normalisation.normaliserTexte(nomAttraction).equals(nomNorm)) {
          return (String) attraction.get("id");
        }
      }
      // Refus attendu (artiste absent de TM, homonyme) : information, jamais anomalie.
      LOG.info("rechercherAttractionId TM — pas de correspondance exacte nom={}", nom);
      return null;
    } catch (Exception e) {
      LOG.warn(
          "rechercherAttractionId TM échoué nom='{}' : {}",
          nom,
          TicketmasterSource.redigerApiKey(e.getMessage()));
      return null;
    }
  }
}
