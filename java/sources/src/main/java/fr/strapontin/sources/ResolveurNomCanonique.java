/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/// Résolution des noms canoniques d'artistes via Deezer.
///
/// Stratégie : Deezer (similarité ≥ 0.7) → nom inchangé. Les résultats sont mis en cache
/// Caffeine TTL 7 jours pour limiter les appels. La résolution de salle retourne toujours le
/// nom inchangé (Deezer ne référence pas les salles).
///
/// @since 2.0.0
/// @author Kwaite
public class ResolveurNomCanonique implements SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(ResolveurNomCanonique.class);

  private static final String DEEZER_BASE = "https://api.deezer.com";
  private static final double SEUIL_CONFIANCE_DEEZER = 0.7;

  /// Source de résolution utilisée pour un nom canonique.
  public enum SourceResolution {
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
  // Bilan de panne agrégé, remonté dans logs_admin par le worker (#912).
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /// Crée une instance avec les URLs distantes et un cache TTL 7 jours.
  public ResolveurNomCanonique() {
    this(
        RestClient.create(),
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

  @SuppressWarnings("unchecked")
  private String rechercherDeezerArtiste(String nom) {
    String uri = DEEZER_BASE + "/search/artist?q=" + encode(nom) + "&limit=1";
    compteurBilan.enregistrerAppel();
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
      if (!resultat.equals(nom)) {
        log.info("Deezer résolution artiste='{}' → '{}'", nom, resultat);
      }
      return resultat;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Deezer erreur artiste='{}' : {}", nom, e.getMessage());
      return null;
    }
  }

  /// Retourne le bilan agrégé des appels Deezer depuis le dernier prélèvement (#912) et remet
  /// les compteurs à zéro.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  private static String encode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }

  /// Résout le nom canonique d'un artiste ou d'une salle.
  ///
  /// Les résultats sont mis en cache par clé `"type:nom"`.
  /// La résolution de salle retourne toujours `INCHANGE` (Deezer ne référence pas les salles).
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
      String nomDeezer = rechercherDeezerArtiste(nom);
      resultat =
          nomDeezer != null
              ? new ResultatResolution(nomDeezer, SourceResolution.DEEZER)
              : new ResultatResolution(nom, SourceResolution.INCHANGE);
    } else {
      resultat = new ResultatResolution(nom, SourceResolution.INCHANGE);
    }

    cache.put(cleCache, resultat);
    return resultat;
  }
}
