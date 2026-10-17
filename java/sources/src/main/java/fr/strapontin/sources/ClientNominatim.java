/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Client de géocodage Nominatim (OSM), public — réutilisable depuis
 * {@code worker} en dernier recours, contrairement à {@code
 * ClientSparqlWikidata} (interne aux sources Wikidata). Cadencé en
 * interne (1 req/s, {@link #DELAI_MIN_ENTRE_APPELS_MS}).
 * @since 2.9.0
 * @author Kwaite
 */
public final class ClientNominatim {

  private static final Logger LOG = LoggerFactory.getLogger(ClientNominatim.class);

  private static final String BASE_URL = "https://nominatim.openstreetmap.org/search";
  private static final String USER_AGENT =
      "Strapontin/2.9.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr)";

  /** Écart minimal entre deux appels, à respecter par tous les processus de la même adresse. */
  public static final long DELAI_MIN_ENTRE_APPELS_MS = 1000;

  // Environ 2 km de côté à nos latitudes : une position publiée peut s'écarter
  // de quelques centaines de mètres de l'objet OSM.
  private static final double DEMI_COTE_ZONE_DEGRES = 0.01;
  private static final int TAILLE_URL_RECHERCHE = 256;

  private final RestClient restClient;
  private final CadenceurAppels cadenceur;

  /**
   * Crée un client avec un RestClient par défaut (timeout connexion 5s, lecture
   * 10s).
   */
  public ClientNominatim() {
    this(construireRestClientParDefaut(), new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS));
  }

  /**
   * Crée un client dont le cadencement est confié à l'appelant (cadenceur
   * partagé entre processus).
   *
   * @param cadenceur limiteur de débit à utiliser
   */
  public ClientNominatim(final CadenceurAppels cadenceur) {
    this(construireRestClientParDefaut(), cadenceur);
  }

  /**
   * Crée un client avec un RestClient et un cadenceur personnalisés (injection
   * de test).
   *
   * @param restClient client HTTP à utiliser
   * @param cadenceur limiteur de débit à utiliser
   */
  /* package-private : injection de test (RestClient/CadenceurAppels
   * personnalisés). */
  ClientNominatim(final RestClient restClient, final CadenceurAppels cadenceur) {
    this.restClient = restClient;
    this.cadenceur = cadenceur;
  }

  private static RestClient construireRestClientParDefaut() {
    return RestClient.builder()
        .requestFactory(
            ClientHttpFactory.creerRequestFactory(Duration.ofSeconds(5), Duration.ofSeconds(10)))
        .build();
  }

  /**
   * Coordonnées géographiques d'un lieu géocodé.
   */
  public record Coordonnees(double latitude, double longitude) {}

  /**
   * Géocode un nom de lieu en France — meilleur résultat Nominatim.
   * @param nom texte à géocoder (ex. nom de salle brut, approximatif)
   * @return coordonnées du meilleur résultat, vide si aucun/erreur (log)
   */
  public Optional<Coordonnees> geocoder(final String nom) {
    cadenceur.attendre();
    try {
      final String url =
          BASE_URL
              + "?q="
              + URLEncoder.encode(nom, StandardCharsets.UTF_8)
              + "&format=json&limit=1&countrycodes=fr";
      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> resultats =
          restClient.get().uri(url).header("User-Agent", USER_AGENT).retrieve().body(List.class);
      return extraireCoordonnees(resultats);
    } catch (Exception e) {
      LOG.warn("Nominatim géocodage échoué nom={} : {}", nom, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Objet OpenStreetMap trouvé par une recherche : nom, position et étiquettes
   * ({@code wikidata}, {@code website}, {@code capacity}…).
   * @param nom nom de l'objet
   * @param position position de l'objet
   * @param etiquettes étiquettes OSM complémentaires, jamais null
   */
  public record LieuOsm(String nom, Coordonnees position, Map<String, String> etiquettes) {}

  /**
   * Résultat d'une recherche : lieux trouvés, et succès de l'appel (un échec
   * n'est pas une absence de lieu).
   * @param lieux lieux trouvés, du plus pertinent au moins pertinent
   * @param succes {@code false} si l'appel a échoué
   */
  public record ResultatRecherche(List<LieuOsm> lieux, boolean succes) {}

  /**
   * Recherche des lieux par nom, avec leurs étiquettes OSM, dans un carré
   * d'environ 2 km autour d'une position quand elle est connue.
   * @param requete texte recherché (ex. « nom, ville »)
   * @param centre position autour de laquelle chercher, ou null
   * @return lieux trouvés et succès de l'appel
   */
  @SuppressWarnings("unchecked")
  public ResultatRecherche rechercherLieux(final String requete, final Coordonnees centre) {
    cadenceur.attendre();
    try {
      final StringBuilder url =
          new StringBuilder(TAILLE_URL_RECHERCHE)
              .append(BASE_URL)
              .append("?q=")
              .append(URLEncoder.encode(requete, StandardCharsets.UTF_8))
              .append("&format=jsonv2&extratags=1&limit=5&countrycodes=fr");
      if (centre != null) {
        url.append("&bounded=1&viewbox=")
            .append(
                String.format(
                    Locale.ROOT,
                    "%.5f,%.5f,%.5f,%.5f",
                    centre.longitude() - DEMI_COTE_ZONE_DEGRES,
                    centre.latitude() + DEMI_COTE_ZONE_DEGRES,
                    centre.longitude() + DEMI_COTE_ZONE_DEGRES,
                    centre.latitude() - DEMI_COTE_ZONE_DEGRES));
      }
      final List<Map<String, Object>> resultats =
          restClient
              .get()
              .uri(url.toString())
              .header("User-Agent", USER_AGENT)
              .retrieve()
              .body(List.class);
      final List<LieuOsm> lieux = new ArrayList<>();
      for (final Map<String, Object> r :
          resultats != null ? resultats : List.<Map<String, Object>>of()) {
        extraireCoordonnees(List.of(r))
            .ifPresent(
                position ->
                    lieux.add(
                        new LieuOsm(
                            (String) r.get("name"),
                            position,
                            r.get("extratags") instanceof Map<?, ?> tags
                                ? (Map<String, String>) tags
                                : Map.of())));
      }
      return new ResultatRecherche(lieux, true);
    } catch (Exception e) {
      LOG.warn("Nominatim recherche échouée requete={} : {}", requete, e.getMessage());
      return new ResultatRecherche(List.of(), false);
    }
  }

  private static Optional<Coordonnees> extraireCoordonnees(
      final List<Map<String, Object>> resultats) {
    if (resultats == null || resultats.isEmpty()) {
      return Optional.empty();
    }
    final Map<String, Object> premier = resultats.get(0);
    final Object latBrut = premier.get("lat");
    final Object lonBrut = premier.get("lon");
    if (latBrut == null || lonBrut == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Coordonnees(
              Double.parseDouble(latBrut.toString()), Double.parseDouble(lonBrut.toString())));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
