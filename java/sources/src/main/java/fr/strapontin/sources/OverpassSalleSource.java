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

/// Client Overpass (OSM) pour l'enrichissement initial des salles par nom.
///
/// Recherche par lot les salles de spectacle taguées `amenity=music_venue|arts_centre|theatre`
/// sur le territoire français. Utilisé en première étape : les salles sans {@code wikidataId}
/// connu sont recherchées ici ; si le tag `wikidata=Qxxx` est présent, il alimente directement
/// l'enrichissement Wikidata par Q-ID. Les salles qu'Overpass ne trouve pas (nom absent d'OSM,
/// service indisponible) restent éligibles à la recherche Wikidata directe par nom, voir
/// {@link WikidataSalleSource} — Overpass n'est donc plus un passage obligé.
///
/// ⚠️ L'instance publique de référence (`overpass-api.de`, alias de
/// `gall`/`lambert.openstreetmap.de`) a traversé un incident de surcharge documenté depuis avril
/// 2026 (wiki.openstreetmap.org/wiki/Overpass_API/status) — 504 systématiques pendant plus de 3
/// mois, aucune contribution du cycle nocturne pendant tout cet incident. `overpass.karte.io`
/// (cité dans le tick) n'existe pas (résolution DNS en échec). (19/07/2026) : test en
/// direct de tous les miroirs publics officiels — seul `overpass.osm.ch` (SOSM, association OSM
/// suisse) a répondu correctement (HTTP 200 en 1,7s sur une requête représentative du trafic
/// Strapontin, `kumi.systems`/`openstreetmap.fr`/`private.coffee`/`maps.mail.ru` tous injoignables
/// ou instables au moment du test) — bascule effectuée. Aucune politique de rate-limit publiée sur
/// cette instance (contrairement à `overpass-api.de` ou `private.coffee`) — délai entre appels
/// porté à {@value #DELAI_MIN_ENTRE_APPELS_MS}ms par prudence, sans contrainte de temps réel
/// (cycle nocturne unique, coût d'un cycle un peu plus long négligeable). Les échecs 504/timeout
/// restent non bloquants : le lot échoué revient simplement vide, sans retry. Les salles non
/// enrichies restent candidates pour les cycles nocturnes suivants.
///
/// @since 2.6.0
/// @author Kwaite
public class OverpassSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(OverpassSalleSource.class);

  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /// Retourne le bilan agrégé des appels HTTP Overpass depuis le dernier prélèvement et
  /// remet les compteurs à zéro — panne de 3+ mois de l'instance publique jusque-là
  /// invisible dans {@code logs_admin}, seul un grep manuel de journalctl permettait de la
  /// constater.
  ///
  /// @return le bilan depuis le dernier prélèvement
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  // (19/07/2026) : overpass-api.de en panne 3+ mois (504 systématique), overpass.osm.ch seul
  // miroir public répondant correctement au test en direct — voir Javadoc de la classe.
  private static final String BASE_URL = "https://overpass.osm.ch/api/interpreter";
  // Identification obligatoire côté serveur (User-Agent générique/absent → 406 sur l'instance
  // principale, cf. wiki.openstreetmap.org/wiki/Overpass_API).
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  private static final int TAILLE_LOT = 25;
  // porté de 500ms à 1750ms — overpass.osm.ch ne publie aucune politique de rate-limit,
  // contrairement à overpass-api.de ; prudence sans coût réel (cycle nocturne unique, 3h du matin).
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 1750;
  // Le timeout serveur ([timeout:...] dans la requête OverpassQL) doit rester strictement
  // inférieur au timeout de lecture HTTP client pour laisser le temps à Overpass de renvoyer sa
  // propre réponse d'erreur "Query timed out" plutôt que de couper la connexion en premier.
  private static final int TIMEOUT_SERVEUR_SECONDES = 55;
  private static final Duration TIMEOUT_LECTURE_CLIENT = Duration.ofSeconds(70);

  private static final Pattern METACARACTERES_REGEX = Pattern.compile("([.^$|()\\[\\]{}*+?\\\\])");
  private static final Pattern APOSTROPHE_DROITE = Pattern.compile("'");

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
      compteurBilan.enregistrerErreur();
      log.warn("Overpass erreur lot taille={} : {}", lot.size(), e.getMessage());
      return Map.of();
    }
  }

  // OSM tague fréquemment ses noms avec l'apostrophe typographique courbe (’, U+2019) plutôt que
  // l'apostrophe droite (', U+0027) utilisée dans les noms Strapontin (issus de
  // TM/Eventim/Digitick).
  // Le filtre ["name"~"regex"] est appliqué côté serveur Overpass sur la chaîne brute : sans cette
  // substitution, une regex contenant l'apostrophe droite ne matche jamais un nom OSM en apostrophe
  // courbe (constaté sur « L'Empreinte » à Savigny-le-Temple). La normalisation de
  // Normalisation.normaliserTexte, elle, n'intervient qu'après réception de la réponse
  // (mapperReponse)
  // et ne peut pas rattraper un élément qu'Overpass n'a jamais renvoyé.
  private static String echapperRegex(String nom) {
    String echappe = METACARACTERES_REGEX.matcher(nom).replaceAll("\\\\$1");
    return APOSTROPHE_DROITE.matcher(echappe).replaceAll("['’]");
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
