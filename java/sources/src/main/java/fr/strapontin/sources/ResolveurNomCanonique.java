/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.DistanceTexte;
import fr.strapontin.core.Normalisation;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Résolution des noms canoniques d'artistes via Deezer — similarité ≥
 * 0.7, résultats en cache Caffeine (TTL 7j). Salles : toujours le nom
 * inchangé (Deezer ne les référence pas).
 * @since 2.0.0
 * @author Kwaite
 */
public class ResolveurNomCanonique implements SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurNomCanonique.class);

  private static final String DEEZER_BASE = "https://api.deezer.com";
  // Un seuil trop permissif expose au cas où le vrai artiste n'apparaît pas du
  // tout dans le pool de candidats Deezer : seul un homonyme sans rapport mais
  // mieux référencé franchit alors le seuil et est retenu par défaut. Constaté
  // à 0.7 sur des paires réellement corrompues en production (similarité
  // Levenshtein normalisée) : Monolord/Jon Lord=0.750, The Brooks/The
  // Kooks=0.800, Sara Correia/Sara Carreira=0.846 — toutes en dessous de 0.85.
  // Un vrai rapprochement légitime (accent, casse, article) reste identique
  // après normalisation (similarité 1.0), jamais dans cette zone grise.
  private static final double SEUIL_CONFIANCE_DEEZER = 0.85;
  // Un seul résultat ne suffit pas à distinguer un artiste connu d'un homonyme
  // marginal : Deezer classe ses résultats par pertinence textuelle, pas par
  // popularité — une recherche "Young Thug" peut renvoyer en 1ère position
  // "Young T.H.U.G." (id=1062315, nb_fan=323) avant le vrai rappeur
  // (id=1590752, nb_fan=1 547 139), présent en 2e position. Élargir la fenêtre
  // de candidats permet de les comparer entre eux plutôt que de prendre le
  // premier venu.
  private static final int LIMITE_CANDIDATS_DEEZER = 5;
  private static final String TYPE_ARTISTE = "artiste";

  private final RestClient restClient;
  private final Cache<String, ResultatResolution> cache;
  // Bilan de panne agrégé, remonté dans logs_admin par le worker.
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /** Source de résolution utilisée pour un nom canonique. */
  public enum SourceResolution {
    DEEZER,
    INCHANGE
  }

  /**
   * Résultat de la résolution : nom canonique et source utilisée.
   *
   * @param nom nom canonique résolu
   * @param source source ayant fourni le nom
   */
  public record ResultatResolution(String nom, SourceResolution source) {}

  /**
   * Crée une instance avec les URLs distantes, un RestClient borné (timeout
   * connexion 10s, lecture 30s — sans borne, un hoquet réseau ferait pendre
   * indéfiniment un thread worker, même classe d'incident que celle déjà
   * corrigée pour TM/Eventim, #887) et un cache TTL 7 jours.
   */
  public ResolveurNomCanonique() {
    this(
        RestClient.builder()
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build(),
        Caffeine.newBuilder().expireAfterWrite(7, TimeUnit.DAYS).maximumSize(10_000).build());
  }

  /**
   * Crée une instance avec un RestClient et un cache personnalisés (injection
   * de test).
   *
   * @param restClient client HTTP à utiliser
   * @param cache cache Caffeine à utiliser
   */
  public ResolveurNomCanonique(
      final RestClient restClient, final Cache<String, ResultatResolution> cache) {
    this.restClient = restClient;
    this.cache = cache;
  }

  @SuppressWarnings("unchecked")
  private String rechercherDeezerArtiste(final String nom) {
    final String uri =
        DEEZER_BASE + "/search/artist?q=" + encode(nom) + "&limit=" + LIMITE_CANDIDATS_DEEZER;
    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return null;
      }
      final List<Map<String, Object>> dataList = (List<Map<String, Object>>) data.get("data");
      if (dataList == null || dataList.isEmpty()) {
        return null;
      }

      final Map<String, Object> meilleurCandidat = trouverMeilleurCandidatDeezer(dataList, nom);
      if (meilleurCandidat == null) {
        return null;
      }

      final String resultat = (String) meilleurCandidat.get("name");
      if (!resultat.equals(nom)) {
        LOG.info("Deezer résolution artiste='{}' → '{}'", nom, resultat);
      }
      return resultat;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Deezer erreur artiste='{}' : {}", nom, e.getMessage());
      return null;
    }
  }

  /**
   * Accumulateur mutable du meilleur candidat approximatif et du meilleur
   * candidat exact.
   */
  private static final class MeilleursCandidatsDeezer {
    private Map<String, Object> meilleur;
    private Map<String, Object> meilleurExact;
    private double meilleurePopularite = -1;
    private double meilleurePopulariteExacte = -1;

    private Map<String, Object> resultat() {
      return meilleurExact != null ? meilleurExact : meilleur;
    }
  }

  /**
   * Sélectionne le meilleur candidat Deezer (correspondance exacte
   * toujours prioritaire sur un homonyme mieux référencé, détail :
   * CLAUDE.md, Dédoublonnage inter-sources).
   * @param dataList résultats bruts de la recherche Deezer
   * @param nom nom recherché (non normalisé)
   * @return le candidat retenu, ou null si aucun ne dépasse le seuil
   */
  private Map<String, Object> trouverMeilleurCandidatDeezer(
      final List<Map<String, Object>> dataList, final String nom) {
    final String nomNormalise = Normalisation.normaliserTexte(nom);
    final MeilleursCandidatsDeezer acc = new MeilleursCandidatsDeezer();
    for (final Map<String, Object> candidat : dataList) {
      evaluerCandidatDeezer(acc, candidat, nomNormalise);
    }
    return acc.resultat();
  }

  /**
   * Une correspondance exacte l'emporte toujours sur un homonyme approximatif
   * plus populaire ; la popularité ne départage qu'entre candidats eux-mêmes
   * exacts.
   */
  private static void evaluerCandidatDeezer(
      final MeilleursCandidatsDeezer acc,
      final Map<String, Object> candidat,
      final String nomNormalise) {
    final String nomCandidat = (String) candidat.get("name");
    if (nomCandidat == null) {
      return;
    }
    final String nomCandidatNormalise = Normalisation.normaliserTexte(nomCandidat);
    final double similarite = DistanceTexte.similarite(nomNormalise, nomCandidatNormalise);
    if (similarite < SEUIL_CONFIANCE_DEEZER) {
      return;
    }
    final double popularite = popularite(candidat);
    if (acc.meilleur == null || popularite > acc.meilleurePopularite) {
      acc.meilleur = candidat;
      acc.meilleurePopularite = popularite;
    }
    if (nomCandidatNormalise.equals(nomNormalise)
        && (acc.meilleurExact == null || popularite > acc.meilleurePopulariteExacte)) {
      acc.meilleurExact = candidat;
      acc.meilleurePopulariteExacte = popularite;
    }
  }

  private static double popularite(final Map<String, Object> candidat) {
    final Object nbFan = candidat.get("nb_fan");
    return nbFan instanceof Number nombre ? nombre.doubleValue() : 0;
  }

  /**
   * Retourne le bilan agrégé des appels Deezer depuis le dernier prélèvement et
   * remet les compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  private static String encode(final String texte) {
    return URLEncoder.encode(texte, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Résout le nom canonique d'un artiste/salle — cache par clé
   * "type:nom", résolution salle toujours {@code INCHANGE}.
   * @param type "artiste" ou "salle"
   * @param nom nom brut à résoudre
   * @return résultat contenant le nom canonique et la source utilisée
   */
  public ResultatResolution resoudreNomCanonique(final String type, final String nom) {
    final String cleCache = type + ":" + nom;
    final ResultatResolution cached = cache.getIfPresent(cleCache);
    if (cached != null) {
      return cached;
    }

    final ResultatResolution resultat;
    if (TYPE_ARTISTE.equals(type)) {
      final String nomDeezer = rechercherDeezerArtiste(nom);
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
