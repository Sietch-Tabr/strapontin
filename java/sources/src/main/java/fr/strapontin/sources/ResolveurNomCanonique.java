/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/// Résolution des noms canoniques d'artistes et de salles via MusicBrainz et Deezer.
///
/// <p>Stratégie : MusicBrainz (score ≥ 90 + similarité ≥ 0.6) → Deezer (similarité ≥ 0.7) →
/// nom inchangé. Les résultats sont mis en cache Caffeine TTL 7 jours pour limiter les appels.
///
/// <p>MusicBrainz impose 1 req/s : le délai de {@value #DELAI_MB_MS} ms est toujours respecté,
/// même en cas d'erreur, pour éviter le bannissement d'IP.
///
/// @since 2.0.0
/// @author Kwaite
public class ResolveurNomCanonique {

  private static final Logger log = LoggerFactory.getLogger(ResolveurNomCanonique.class);

  private static final String MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
  private static final String DEEZER_BASE = "https://api.deezer.com";
  private static final long DELAI_MB_MS = 1100;
  private static final double SEUIL_CONFIANCE_MB = 0.6;
  private static final double SEUIL_CONFIANCE_DEEZER = 0.7;

  /// Source de résolution utilisée pour un nom canonique.
  public enum SourceResolution {
    MUSICBRAINZ,
    DEEZER,
    INCHANGE
  }

  /// Résultat de la résolution : nom canonique et source utilisée.
  ///
  /// @param nom    nom canonique résolu
  /// @param source source ayant fourni le nom
  public record ResultatResolution(String nom, SourceResolution source) {}

  private final RestClient restClient;
  private final Cache<String, ResultatResolution> cache;

  /// Crée une instance avec les URLs distantes et un cache TTL 7 jours.
  ///
  /// @param contactEmail email de contact pour le User-Agent MusicBrainz
  public ResolveurNomCanonique(String contactEmail) {
    this(
        RestClient.builder()
            .defaultHeader("User-Agent", "Strapontin/2.0 (" + contactEmail + ")")
            .build(),
        Caffeine.newBuilder().expireAfterWrite(7, TimeUnit.DAYS).maximumSize(10_000).build());
  }

  /// Crée une instance avec un RestClient et un cache personnalisés (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  /// @param cache      cache Caffeine à utiliser
  public ResolveurNomCanonique(RestClient restClient, Cache<String, ResultatResolution> cache) {
    this.restClient = restClient;
    this.cache = cache;
  }

  private void attendreMB() {
    try {
      Thread.sleep(DELAI_MB_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressWarnings("unchecked")
  private String rechercherMBArtiste(String nom) {
    String uri = MUSICBRAINZ_BASE + "/artist/?query=" + encode(nom) + "&fmt=json&limit=1";
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      attendreMB();
      if (data == null) return null;
      var artists = (List<Map<String, Object>>) data.get("artists");
      if (artists == null || artists.isEmpty()) return null;
      var artiste = artists.get(0);
      int score = artiste.get("score") != null ? ((Number) artiste.get("score")).intValue() : 0;
      if (score < 90) return null;
      String nomMB = (String) artiste.get("name");
      if (Normalisation.similarite(
              Normalisation.normaliserTexte(nom), Normalisation.normaliserTexte(nomMB))
          < SEUIL_CONFIANCE_MB) {
        return null;
      }
      return nomMB;
    } catch (Exception e) {
      attendreMB();
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private String rechercherMBSalle(String nom) {
    String uri = MUSICBRAINZ_BASE + "/place/?query=" + encode(nom) + "&fmt=json&limit=1";
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      attendreMB();
      if (data == null) return null;
      var places = (List<Map<String, Object>>) data.get("places");
      if (places == null || places.isEmpty()) return null;
      var lieu = places.get(0);
      int score = lieu.get("score") != null ? ((Number) lieu.get("score")).intValue() : 0;
      if (score < 90) return null;
      return (String) lieu.get("name");
    } catch (Exception e) {
      attendreMB();
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private String rechercherDeezerArtiste(String nom) {
    String uri = DEEZER_BASE + "/search/artist?q=" + encode(nom) + "&limit=1";
    try {
      Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) return null;
      var dataList = (List<Map<String, Object>>) data.get("data");
      if (dataList == null || dataList.isEmpty()) return null;
      String resultat = (String) dataList.get(0).get("name");
      if (resultat == null) return null;
      if (Normalisation.similarite(
              Normalisation.normaliserTexte(nom), Normalisation.normaliserTexte(resultat))
          < SEUIL_CONFIANCE_DEEZER) {
        return null;
      }
      return resultat;
    } catch (Exception e) {
      return null;
    }
  }

  private static String encode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }

  /// Résout le nom canonique d'un artiste ou d'une salle.
  ///
  /// <p>Les résultats sont mis en cache par clé {@code "type:nom"}.
  ///
  /// @param type {@code "artiste"} ou {@code "salle"}
  /// @param nom  nom brut à résoudre
  /// @return résultat contenant le nom canonique et la source utilisée
  public ResultatResolution resoudreNomCanonique(String type, String nom) {
    String cleCache = type + ":" + nom;
    ResultatResolution cached = cache.getIfPresent(cleCache);
    if (cached != null) return cached;

    ResultatResolution resultat;
    if ("artiste".equals(type)) {
      String nomMB = rechercherMBArtiste(nom);
      if (nomMB != null) {
        resultat = new ResultatResolution(nomMB, SourceResolution.MUSICBRAINZ);
      } else {
        String nomDeezer = rechercherDeezerArtiste(nom);
        resultat =
            nomDeezer != null
                ? new ResultatResolution(nomDeezer, SourceResolution.DEEZER)
                : new ResultatResolution(nom, SourceResolution.INCHANGE);
      }
    } else {
      String nomMB = rechercherMBSalle(nom);
      resultat =
          nomMB != null
              ? new ResultatResolution(nomMB, SourceResolution.MUSICBRAINZ)
              : new ResultatResolution(nom, SourceResolution.INCHANGE);
    }

    cache.put(cleCache, resultat);
    return resultat;
  }
}
