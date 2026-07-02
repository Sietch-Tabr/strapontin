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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/// Client Overpass (OSM) pour l'enrichissement initial des salles par nom (#711).
///
/// Recherche par lot les salles de spectacle taguées `amenity=music_venue|arts_centre|theatre`
/// sur le territoire français. Utilisé en première étape : les salles sans {@code wikidataId}
/// connu sont recherchées ici ; si le tag `wikidata=Qxxx` est présent, il alimente directement
/// l'enrichissement Wikidata par Q-ID. Les salles qu'Overpass ne trouve pas (nom absent d'OSM,
/// service indisponible) restent éligibles à la recherche Wikidata directe par nom, voir
/// {@link WikidataSalleSource} — Overpass n'est donc plus un passage obligé.
///
/// ⚠️ L'instance publique Overpass (`overpass-api.de`, alias de `gall`/`lambert.openstreetmap.de`)
/// traverse un incident de surcharge documenté depuis avril 2026 — durée annoncée ~8 semaines
/// (wiki.openstreetmap.org/wiki/Overpass_API/status) : trafic massif depuis des IPs Azure/AWS,
/// désormais partiellement bloquées. Conséquence observée en juillet 2026 : les requêtes
/// utilisant `area["ISO3166-1"="FR"]` (calcul de polygone administratif, coûteux) restent
/// bloquées bien au-delà de leur propre timeout déclaré, d'où l'usage d'une bounding box
/// (nettement moins chère) à la place. Même avec cette optimisation, des échecs 504/timeout
/// restent attendus tant que l'incident n'est pas résorbé, et sont non bloquants : le lot échoué
/// revient simplement vide, sans retry (l'étiquette Overpass demande de la retenue plutôt que des
/// re-tentatives agressives sur un service public déjà saturé). Les salles non enrichies restent
/// candidates pour les cycles nocturnes suivants.
///
/// @since 2.6.0
/// @author Kwaite
public class OverpassSalleSource implements SourceEnrichissementSalle {

  private static final Logger log = LoggerFactory.getLogger(OverpassSalleSource.class);

  // "overpass.karte.io" (cité dans le ticket #711) n'existe pas — vérifié par résolution DNS
  // (échec depuis le LXC dev ET en dehors) et absent de toute liste de miroirs Overpass connue.
  // overpass-api.de est l'instance de référence officielle
  // (wiki.openstreetmap.org/wiki/Overpass_API).
  private static final String BASE_URL = "https://overpass-api.de/api/interpreter";
  // Identification obligatoire côté serveur (User-Agent générique/absent → 406 sur l'instance
  // principale, cf. wiki.openstreetmap.org/wiki/Overpass_API).
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  private static final int TAILLE_LOT = 25;
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 500;
  // Le timeout serveur ([timeout:...] dans la requête OverpassQL) doit rester strictement
  // inférieur au timeout de lecture HTTP client pour laisser le temps à Overpass de renvoyer sa
  // propre réponse d'erreur "Query timed out" plutôt que de couper la connexion en premier.
  private static final int TIMEOUT_SERVEUR_SECONDES = 55;
  private static final Duration TIMEOUT_LECTURE_CLIENT = Duration.ofSeconds(70);

  private static final Pattern METACARACTERES_REGEX = Pattern.compile("([.^$|()\\[\\]{}*+?\\\\])");

  private final RestClient restClient;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// Crée une instance avec un RestClient par défaut (timeout connexion 5s, lecture 70s).
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
    List<SalleAEnrichir> aTraiter = salles.stream().filter(s -> s.wikidataId() == null).toList();
    if (aTraiter.isEmpty()) return Map.of();

    Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    for (int i = 0; i < aTraiter.size(); i += TAILLE_LOT) {
      List<SalleAEnrichir> lot = aTraiter.subList(i, Math.min(i + TAILLE_LOT, aTraiter.size()));
      attendreRateLimitGlobal();
      resultats.putAll(enrichirUnLot(lot));
    }
    return resultats;
  }

  private Map<UUID, EnrichissementSalle> enrichirUnLot(List<SalleAEnrichir> lot) {
    String requete = construireRequeteOverpassQL(lot);
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
      log.warn("Overpass erreur lot taille={} : {}", lot.size(), e.getMessage());
      return Map.of();
    }
  }

  private static String echapperRegex(String nom) {
    return METACARACTERES_REGEX.matcher(nom).replaceAll("\\\\$1");
  }

  // Bounding box englobant la France métropolitaine (marge incluse). Volontairement PAS
  // area["ISO3166-1"="FR"][admin_level=2] : le calcul du polygone administratif exact est l'une
  // des opérations les plus coûteuses d'Overpass et échoue quasi systématiquement (timeout serveur
  // dépassé sans réponse) sur l'instance publique actuellement en surcharge (cf. commentaire de
  // classe). Une bbox est une opération spatiale bien moins chère, vérifiée fonctionnelle en
  // juillet 2026 sur le même cluster (gall/lambert.openstreetmap.de) là où l'area échouait.
  private static final String BBOX_FRANCE = "41.0,-5.5,51.5,10.0";

  private static String construireRequeteOverpassQL(List<SalleAEnrichir> lot) {
    String nomsRegex =
        lot.stream().map(s -> echapperRegex(s.nom())).collect(Collectors.joining("|"));
    return """
    [out:json][timeout:%d];
    (
      nwr["amenity"~"^(music_venue|arts_centre|theatre)$"]["name"~"%s",i](%s);
    );
    out tags center;
    """
        .formatted(TIMEOUT_SERVEUR_SECONDES, nomsRegex, BBOX_FRANCE);
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
      try {
        Thread.sleep(prochainAppel - maintenant);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }
}
