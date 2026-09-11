/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.Normalisation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Client Deezer pour l'enrichissement des artistes (étape 3).
 *
 * <p>Deux modes, indépendants l'un de l'autre :
 *
 * <ul>
 *   <li>{@code deezerId} connu (renseigné au préalable par {@link WikidataArtisteSource} via P2722)
 *       → confirmation directe du nom canonique par {@code GET /artist/{id}}.
 *   <li>{@code deezerId} absent → recherche par nom ({@code GET /search/artist?q=}). Le P2722
 *       Wikidata est fréquemment absent en pratique (constaté en production sur Yelle, Mademoiselle
 *       K, Ycare — Deezer référence pourtant bien ces artistes) : ne pas dépendre uniquement de
 *       Wikidata pour peupler {@code deezer_id} reviendrait à ignorer une source de données déjà
 *       exploitée ailleurs dans le projet.
 * </ul>
 *
 * Seule une correspondance exacte de nom normalisé est retenue (pas de seuil de similarité comme
 * dans {@link ResolveurNomCanonique}) : cette découverte assigne un identifiant persistant, un
 * enjeu plus élevé qu'une simple confirmation cosmétique de nom affiché — même prudence que {@code
 * TicketmasterSource.rechercherAttractionId} (anti- JAIN/ANUV JAIN).
 *
 * @since 2.6.0
 * @author Kwaite
 */
public class DeezerArtisteSource implements SourceEnrichissementArtiste, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(DeezerArtisteSource.class);

  private static final String DEEZER_BASE = "https://api.deezer.com";

  private final RestClient restClient;
  private final fr.strapontin.core.CompteurBilanFetch compteurBilan =
      new fr.strapontin.core.CompteurBilanFetch();

  /**
   * Retourne le bilan agrégé des appels HTTP Deezer depuis le dernier prélèvement.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public fr.strapontin.core.BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * Crée une instance avec un RestClient par défaut (timeout connexion 10s, lecture 30s) — sans
   * borne, un hoquet réseau ou une réponse lente de l'API Deezer ferait pendre indéfiniment un
   * thread worker (même classe d'incident que celle déjà corrigée pour TM/Eventim, #887).
   */
  public DeezerArtisteSource() {
    this(
        RestClient.builder()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build());
  }

  /**
   * Crée une instance avec un RestClient personnalisé (injection de test).
   *
   * @param restClient client HTTP à utiliser
   */
  public DeezerArtisteSource(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String nom() {
    return "deezer";
  }

  @Override
  public Map<UUID, EnrichissementArtiste> enrichirLot(List<ArtisteAEnrichir> artistes) {
    Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();
    for (ArtisteAEnrichir artiste : artistes) {
      Optional<EnrichissementArtiste> enrichissement =
          artiste.deezerId() != null ? confirmerNom(artiste) : decouvrirParNom(artiste);
      enrichissement.ifPresent(e -> resultats.put(artiste.id(), e));
    }
    return resultats;
  }

  @SuppressWarnings("unchecked")
  private Optional<EnrichissementArtiste> decouvrirParNom(ArtisteAEnrichir artiste) {
    compteurBilan.enregistrerAppel();
    try {
      String uri = DEEZER_BASE + "/search/artist?q=" + encode(artiste.nom()) + "&limit=5";
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return Optional.empty();
      var dataList = (List<Map<String, Object>>) data.get("data");
      if (dataList == null) return Optional.empty();

      for (var candidat : dataList) {
        String nomCandidat = (String) candidat.get("name");
        if (nomCandidat == null
            || !Normalisation.normaliserTexte(nomCandidat).equals(artiste.nomNorm())) {
          continue;
        }
        Object idCandidat = candidat.get("id");
        if (idCandidat == null) continue;
        return Optional.of(
            new EnrichissementArtiste(
                artiste.wikidataId(), nomCandidat, String.valueOf(idCandidat), null, null));
      }
      return Optional.empty();
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Deezer découverte erreur nom={} : {}", artiste.nom(), e.getMessage());
      return Optional.empty();
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Optional<EnrichissementArtiste> confirmerNom(ArtisteAEnrichir artiste) {
    compteurBilan.enregistrerAppel();
    try {
      Map<String, Object> data =
          restClient
              .get()
              .uri(DEEZER_BASE + "/artist/" + artiste.deezerId())
              .retrieve()
              .body(Map.class);
      if (data == null || data.get("name") == null || data.containsKey("error")) {
        return Optional.empty();
      }
      String nomCanonique = (String) data.get("name");
      return Optional.of(
          new EnrichissementArtiste(
              artiste.wikidataId(), nomCanonique, artiste.deezerId(), null, null));
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Deezer erreur artistId={} : {}", artiste.deezerId(), e.getMessage());
      return Optional.empty();
    }
  }
}
