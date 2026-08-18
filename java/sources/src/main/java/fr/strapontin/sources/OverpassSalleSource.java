/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.Normalisation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/// Client Overpass (OSM) pour l'enrichissement initial des salles par nom.
///
/// Recherche par lot les salles de spectacle taguées `amenity=music_venue|arts_centre|theatre`.
/// Utilisé en première étape : les salles sans {@code wikidataId} connu sont recherchées ici ; si
/// le tag `wikidata=Qxxx` est présent, il alimente directement l'enrichissement Wikidata par Q-ID.
/// Les salles qu'Overpass ne trouve pas (nom absent d'OSM, service indisponible) restent éligibles
/// à la recherche Wikidata directe par nom, voir {@link WikidataSalleSource} — Overpass n'est donc
/// plus un passage obligé.
///
/// Chaque lot interroge une zone géographique <strong>par département</strong> (rayon `around:`
/// centré sur {@link TicketmasterSource#CENTRES_DEPT}) plutôt qu'une bounding box couvrant la
/// France entière : une requête sur tout le territoire échoue quasi systématiquement par timeout
/// sur les instances publiques Overpass (surchargées), alors que la même requête restreinte à un
/// département répond en 2-3s. Un basculement précédent vers `overpass.osm.ch` (association OSM
/// suisse) s'est révélé une impasse — cette instance ne couvre en réalité que la Suisse (+ 10 km de
/// frontière), confirmé en test direct (0 résultat sur des salles françaises pourtant bien
/// référencées sur OSM) : un succès HTTP `200 OK` avec un tableau d'éléments vide avait été
/// confondu avec un succès fonctionnel, masquant 12 jours de contribution nulle sans la moindre
/// erreur journalisée. Les salles sans département connu ne sont pas interrogées (aucune zone à
/// requêter) — elles restent éligibles à la découverte Wikidata par nom, indépendante d'Overpass.
///
/// @since 2.6.0
/// @author Kwaite
public class OverpassSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(OverpassSalleSource.class);

  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /// Retourne le bilan agrégé des appels HTTP Overpass depuis le dernier prélèvement et remet les
  /// compteurs à zéro.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  private static final String BASE_URL = "https://overpass-api.de/api/interpreter";
  // Identification obligatoire côté serveur (User-Agent générique/absent → 406).
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  private static final int TAILLE_LOT = 25;
  // Constaté en test direct (hors cycle nocturne) : le rate-limit (429) et la saturation (504,
  // "server too busy") d'overpass-api.de se déclenchent en quelques secondes déjà à notre propre
  // cadence d'appels — l'instance publique est partagée avec tous ses autres utilisateurs, notre
  // délai minimal entre lots n'a donc qu'un effet partiel sur la contention globale, mais rester
  // en dessous de notre part raisonnable évite d'aggraver une saturation déjà là.
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 3000;
  // Un seul retry suffit à absorber un aléa réseau isolé sans faire pendre le cycle nocturne sur
  // une instance en difficulté passagère. Deux natures d'échec distinctes : 429 (rate-limit,
  // potentiellement partagé avec d'autres utilisateurs de l'instance) où réessayer trop vite ne
  // fait qu'ajouter de la charge à un service déjà saturé — backoff long ; 504/erreur réseau (aléa
  // isolé, souvent résolu en quelques secondes) — backoff court, inchangé depuis 2.7.8.
  private static final int NB_ESSAIS_MAX = 2;
  private static final long DELAI_APRES_ERREUR_MS = 5000;
  private static final long DELAI_APRES_429_MS = 20000;
  // Le timeout serveur ([timeout:...] dans la requête OverpassQL) doit rester strictement
  // inférieur au timeout de lecture HTTP client pour laisser le temps à Overpass de renvoyer sa
  // propre réponse d'erreur "Query timed out" plutôt que de couper la connexion en premier.
  private static final int TIMEOUT_SERVEUR_SECONDES = 25;
  private static final Duration TIMEOUT_LECTURE_CLIENT = Duration.ofSeconds(35);
  // Rayon de recherche autour du centre géographique du département, en mètres — légèrement plus
  // large que le rayon TM (CentreDept#rayon, en km) pour couvrir les salles proches d'une
  // frontière départementale sans avoir à interroger les deux départements voisins.
  private static final int MARGE_RAYON_METRES = 5_000;

  private static final Pattern METACARACTERES_REGEX = Pattern.compile("([.^$|()\\[\\]{}*+?\\\\])");
  private static final Pattern APOSTROPHE_DROITE = Pattern.compile("'");

  private final RestClient restClient;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// Crée une instance avec un RestClient par défaut (timeout connexion 5s, lecture 35s).
  public OverpassSalleSource() {
    this(construireRestClientParDefaut());
  }

  private static RestClient construireRestClientParDefaut() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(TIMEOUT_LECTURE_CLIENT);
    return RestClient.builder().requestFactory(factory).build();
  }

  /// Crée une instance avec un RestClient personnalisé (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  public OverpassSalleSource(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String nom() {
    return "overpass";
  }

  @Override
  public Map<UUID, EnrichissementSalle> enrichirLot(List<SalleAEnrichir> salles) {
    List<SalleAEnrichir> aTraiter =
        salles.stream().filter(s -> s.wikidataId() == null && s.dept() != null).toList();
    int sansDept =
        (int) salles.stream().filter(s -> s.wikidataId() == null).count() - aTraiter.size();
    if (sansDept > 0) {
      log.debug("Overpass — {} salle(s) ignorée(s), département inconnu", sansDept);
    }
    if (aTraiter.isEmpty()) return Map.of();

    Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    Map<String, List<SalleAEnrichir>> parDept =
        aTraiter.stream().collect(Collectors.groupingBy(SalleAEnrichir::dept));
    for (var entree : parDept.entrySet()) {
      CentreDept centre = TicketmasterSource.CENTRES_DEPT.get(entree.getKey());
      if (centre == null) continue;
      List<SalleAEnrichir> sallesDept = entree.getValue();
      for (int i = 0; i < sallesDept.size(); i += TAILLE_LOT) {
        List<SalleAEnrichir> lot =
            sallesDept.subList(i, Math.min(i + TAILLE_LOT, sallesDept.size()));
        attendreRateLimitGlobal();
        resultats.putAll(enrichirUnLot(lot, centre));
      }
    }
    return resultats;
  }

  private Map<UUID, EnrichissementSalle> enrichirUnLot(
      List<SalleAEnrichir> lot, CentreDept centre) {
    String requete = construireRequeteOverpassQL(lot, centre);
    Exception derniereErreur = null;
    for (int essai = 1; essai <= NB_ESSAIS_MAX; essai++) {
      compteurBilan.enregistrerAppel();
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> data =
            restClient
                .post()
                .uri(BASE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("data=" + URLEncoder.encode(requete, StandardCharsets.UTF_8))
                .retrieve()
                .body(Map.class);
        return mapperReponse(data, lot);
      } catch (Exception e) {
        derniereErreur = e;
        compteurBilan.enregistrerErreur();
        if (essai < NB_ESSAIS_MAX) {
          attendre(delaiAvantRetry(e));
        }
      }
    }
    log.warn("Overpass erreur lot taille={} : {}", lot.size(), derniereErreur.getMessage());
    return Map.of();
  }

  /// Détermine le délai avant retry selon la nature de l'échec — accessible aux tests sans
  /// déclencher d'attente réelle.
  ///
  /// @param e exception levée par l'appel HTTP
  /// @return délai en millisecondes avant le prochain essai
  static long delaiAvantRetry(Exception e) {
    return e instanceof HttpClientErrorException.TooManyRequests
        ? DELAI_APRES_429_MS
        : DELAI_APRES_ERREUR_MS;
  }

  private static void attendre(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // OSM tague fréquemment ses noms avec l'apostrophe typographique courbe (’, U+2019) plutôt que
  // l'apostrophe droite (', U+0027) utilisée dans les noms Strapontin (issus de
  // TM/Eventim/Digitick). Le filtre ["name"~"regex"] est appliqué côté serveur Overpass sur la
  // chaîne brute : sans cette substitution, une regex contenant l'apostrophe droite ne matche
  // jamais un nom OSM en apostrophe courbe (constaté sur « L'Empreinte » à Savigny-le-Temple). La
  // normalisation de Normalisation.normaliserTexte, elle, n'intervient qu'après réception de la
  // réponse (mapperReponse) et ne peut pas rattraper un élément qu'Overpass n'a jamais renvoyé.
  private static String echapperRegex(String nom) {
    String echappe = METACARACTERES_REGEX.matcher(nom).replaceAll("\\\\$1");
    return APOSTROPHE_DROITE.matcher(echappe).replaceAll("['’]");
  }

  private static String construireRequeteOverpassQL(List<SalleAEnrichir> lot, CentreDept centre) {
    String nomsRegex =
        lot.stream().map(s -> echapperRegex(s.nom())).collect(Collectors.joining("|"));
    int rayonMetres = centre.rayon() * 1000 + MARGE_RAYON_METRES;
    String around =
        "around:%d,%s,%s"
            .formatted(
                rayonMetres,
                String.format(Locale.ROOT, "%.4f", centre.lat()),
                String.format(Locale.ROOT, "%.4f", centre.lng()));
    return """
    [out:json][timeout:%d];
    (
      nwr["amenity"~"^(music_venue|arts_centre|theatre)$"]["name"~"%s",i](%s);
    );
    out tags center;
    """
        .formatted(TIMEOUT_SERVEUR_SECONDES, nomsRegex, around);
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, EnrichissementSalle> mapperReponse(
      Map<String, Object> data, List<SalleAEnrichir> lot) {
    Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    if (data == null) return resultats;
    var elements = (List<Map<String, Object>>) data.get("elements");
    if (elements == null) return resultats;

    for (SalleAEnrichir salle : lot) {
      for (var element : elements) {
        var tags = (Map<String, String>) element.get("tags");
        if (tags == null) continue;
        String nomTag = tags.get("name");
        if (nomTag == null || !Normalisation.normaliserTexte(nomTag).equals(salle.nomNorm())) {
          continue;
        }
        resultats.put(salle.id(), construireEnrichissement(tags));
        break;
      }
    }
    return resultats;
  }

  private static EnrichissementSalle construireEnrichissement(Map<String, String> tags) {
    Integer capacite = null;
    String capaciteStr = tags.get("capacity");
    if (capaciteStr != null) {
      try {
        capacite = Integer.parseInt(capaciteStr.replaceAll("[^0-9]", ""));
      } catch (NumberFormatException e) {
        capacite = null;
      }
    }
    return new EnrichissementSalle(
        tags.get("wikidata"),
        tags.get("name:fr"),
        tags.get("addr:city"),
        tags.get("website"),
        capacite);
  }

  private synchronized void attendreRateLimitGlobal() {
    long maintenant = System.currentTimeMillis();
    long prochainAppel = dernierAppelMs.get() + DELAI_MIN_ENTRE_APPELS_MS;
    if (maintenant < prochainAppel) {
      attendre(prochainAppel - maintenant);
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }
}
