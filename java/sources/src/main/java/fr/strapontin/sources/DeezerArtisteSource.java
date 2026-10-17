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
 * Client Deezer pour l'enrichissement des artistes (étape 3) — détail
 * (deux modes, seuil de correspondance exacte) : CLAUDE.md,
 * Enrichissement Wikidata/OSM.
 * @since 2.6.0
 * @author Kwaite
 */
public class DeezerArtisteSource implements SourceEnrichissementArtiste, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(DeezerArtisteSource.class);

  private static final String DEEZER_BASE = "https://api.deezer.com";

  private final RestClient restClient;
  private final fr.strapontin.core.CompteurBilanFetch compteurBilan =
      new fr.strapontin.core.CompteurBilanFetch();

  /**
   * Retourne le bilan agrégé des appels HTTP Deezer depuis le dernier
   * prélèvement.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public fr.strapontin.core.BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * Crée une instance avec un RestClient par défaut (timeout connexion 10s,
   * lecture 30s) — sans borne, un hoquet réseau ou une réponse lente de l'API
   * Deezer ferait pendre indéfiniment un thread worker (même classe d'incident
   * que celle déjà corrigée pour TM/Eventim, #887).
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
  public DeezerArtisteSource(final RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String nom() {
    return "deezer";
  }

  @Override
  public Map<UUID, EnrichissementArtiste> enrichirLot(final List<ArtisteAEnrichir> artistes) {
    final Map<UUID, EnrichissementArtiste> resultats = new HashMap<>();
    for (final ArtisteAEnrichir artiste : artistes) {
      final Optional<EnrichissementArtiste> enrichissement =
          artiste.deezerId() != null ? confirmerNom(artiste) : decouvrirParNom(artiste);
      enrichissement.ifPresent(e -> resultats.put(artiste.id(), e));
    }
    return resultats;
  }

  @SuppressWarnings("unchecked")
  private Optional<EnrichissementArtiste> decouvrirParNom(final ArtisteAEnrichir artiste) {
    compteurBilan.enregistrerAppel();
    try {
      final String uri = DEEZER_BASE + "/search/artist?q=" + encode(artiste.nom()) + "&limit=25";
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return Optional.empty();
      }
      final List<Map<String, Object>> dataList = (List<Map<String, Object>>) data.get("data");
      if (dataList == null) {
        return Optional.empty();
      }

      Map<String, Object> retenu = null;
      double fansRetenu = -1;
      for (final Map<String, Object> candidat : dataList) {
        final String nomCandidat = (String) candidat.get("name");
        if (nomCandidat == null
            || candidat.get("id") == null
            || !Normalisation.normaliserTexte(nomCandidat).equals(artiste.nomNorm())) {
          continue;
        }
        // Le plus suivi des homonymes exacts : un artiste récent de quelques dizaines de fans ne
        // prend pas la place du groupe connu.
        final double fans = candidat.get("nb_fan") instanceof Number n ? n.doubleValue() : 0;
        if (fans > fansRetenu) {
          retenu = candidat;
          fansRetenu = fans;
        }
      }
      if (retenu != null) {
        return Optional.of(
            new EnrichissementArtiste(
                artiste.wikidataId(),
                (String) retenu.get("name"),
                String.valueOf(retenu.get("id")),
                null,
                null));
      }
      return Optional.empty();
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Deezer découverte erreur nom={} : {}", artiste.nom(), e.getMessage());
      return Optional.empty();
    }
  }

  private static String encode(final String texte) {
    return URLEncoder.encode(texte, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Optional<EnrichissementArtiste> confirmerNom(final ArtisteAEnrichir artiste) {
    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data =
          restClient
              .get()
              .uri(DEEZER_BASE + "/artist/" + artiste.deezerId())
              .retrieve()
              .body(Map.class);
      if (data == null || data.get("name") == null || data.containsKey("error")) {
        return Optional.empty();
      }
      final String nomCanonique = (String) data.get("name");
      return Optional.of(
          new EnrichissementArtiste(
              artiste.wikidataId(), nomCanonique, artiste.deezerId(), null, null));
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Deezer erreur artistId={} : {}", artiste.deezerId(), e.getMessage());
      return Optional.empty();
    }
  }
}
