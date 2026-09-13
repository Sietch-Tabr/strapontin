/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.Normalisation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Résolution de l'attractionId Ticketmaster correspondant à un nom d'artiste, via l'API
 * attractions.
 *
 * <p>Extrait de {@link TicketmasterSource} pour rester sous la limite de 500 lignes par fichier.
 *
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
  // Une absence de correspondance TM pour un nom donné est un résultat stable
  // la plupart du temps (rappelé à chaque cycle fetch:artistes:batch, aucun
  // état négatif persisté pour permettre une redécouverte si TM ajoute
  // l'attraction plus tard) — sans throttle, le WARN se répète à chaque cycle
  // pour le même nom déjà classé introuvable.
  private final Cache<String, Boolean> avertissementsAttractionIntrouvable;

  /**
   * @param restClient client HTTP Ticketmaster partagé
   * @param apiKey clé API Ticketmaster Discovery v2
   * @param baseUrl URL de base de l'API Ticketmaster Discovery v2
   * @param cadenceur stratégie d'espacement des appels HTTP à utiliser
   * @param avertissementsAttractionIntrouvable cache de throttle des
   *     avertissements
   */
  /* package-private : instancié uniquement par TicketmasterSource (même
   * package). */
  TicketmasterAttractionResolver(
      final RestClient restClient,
      final String apiKey,
      final String baseUrl,
      final CadenceurAppels cadenceur,
      final Cache<String, Boolean> avertissementsAttractionIntrouvable) {
    this.restClient = restClient;
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.cadenceur = cadenceur;
    this.avertissementsAttractionIntrouvable = avertissementsAttractionIntrouvable;
  }

  /**
   * Crée le cache de throttle par défaut des avertissements d'attraction
   * introuvable (expiration 1 jour, taille max 1000).
   *
   * @return un nouveau cache de throttle
   */
  /* package-private : appelé par TicketmasterSource (même package). */
  static Cache<String, Boolean> creerCacheAvertissementsParDefaut() {
    return Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(1)).maximumSize(1_000).build();
  }

  /**
   * Recherche l'identifiant TM (attractionId) pour un artiste par son nom.
   *
   * <p>Parcourt jusqu'à 10 résultats pour trouver la première correspondance exacte normalisée. TM
   * peut trier par popularité et retourner un artiste similaire avant l'artiste recherché (ex:
   * "Shy" avant "Shy'm").
   *
   * @param nom nom de l'artiste
   * @return attractionId TM, ou null si aucun résultat exact dans les 10 premiers
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par TicketmasterSource (même package). */
  String rechercherAttractionId(final String nom) {
    final String uri =
        UriComponentsBuilder.fromUriString(baseUrl + "/attractions.json")
            .queryParam("keyword", nom)
            .queryParam("locale", "*")
            .queryParam("size", 10)
            .queryParam("apikey", apiKey)
            .build()
            .toUriString();
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
      if (avertissementsAttractionIntrouvable.getIfPresent(nomNorm) == null) {
        avertissementsAttractionIntrouvable.put(nomNorm, Boolean.TRUE);
        LOG.warn("rechercherAttractionId TM — pas de correspondance exacte nom={}", nom);
      } else {
        LOG.debug(
            "rechercherAttractionId TM — pas de correspondance exacte nom={} (déjà signalé)", nom);
      }
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
