/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/// Client de géocodage Nominatim (OSM), public — réutilisable depuis le module `worker` en dernier
/// recours avant de créer une salle sans aucune correspondance par nom, contrairement à {@code
/// ClientSparqlWikidata} (interne à `sources`, jamais appelé hors des sources Wikidata).
///
/// <p>Politique d'usage Nominatim (1 requête/seconde) cadencée en interne (voir {@link
/// #DELAI_MIN_ENTRE_APPELS_MS}) — jamais imposée à l'appelant.
///
/// @since 2.9.0
/// @author Kwaite
public final class ClientNominatim {

  private static final Logger log = LoggerFactory.getLogger(ClientNominatim.class);

  private static final String BASE_URL = "https://nominatim.openstreetmap.org/search";
  private static final String USER_AGENT =
      "Strapontin/2.9.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr)";
  private static final long DELAI_MIN_ENTRE_APPELS_MS = 1000;

  private final RestClient restClient;
  private final CadenceurAppels cadenceur;

  /// Crée un client avec un RestClient par défaut (timeout connexion 5s, lecture 10s).
  public ClientNominatim() {
    this(construireRestClientParDefaut(), new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS));
  }

  /// Crée un client avec un RestClient et un cadenceur personnalisés (injection de test).
  ///
  /// @param restClient client HTTP à utiliser
  /// @param cadenceur limiteur de débit à utiliser
  ClientNominatim(RestClient restClient, CadenceurAppels cadenceur) {
    this.restClient = restClient;
    this.cadenceur = cadenceur;
  }

  private static RestClient construireRestClientParDefaut() {
    return RestClient.builder()
        .requestFactory(
            ClientHttpFactory.creerRequestFactory(Duration.ofSeconds(5), Duration.ofSeconds(10)))
        .build();
  }

  /// Coordonnées géographiques d'un lieu géocodé.
  public record Coordonnees(double latitude, double longitude) {}

  /// Géocode un nom de lieu en France — retourne le meilleur résultat Nominatim, s'il existe.
  ///
  /// @param nom texte à géocoder (ex. nom de salle brut, éventuellement approximatif)
  /// @return les coordonnées du meilleur résultat, vide si aucun résultat ou en cas d'erreur
  ///   (journalisée, jamais propagée)
  public Optional<Coordonnees> geocoder(String nom) {
    cadenceur.attendre();
    try {
      String url =
          BASE_URL
              + "?q="
              + URLEncoder.encode(nom, StandardCharsets.UTF_8)
              + "&format=json&limit=1&countrycodes=fr";
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> resultats =
          restClient.get().uri(url).header("User-Agent", USER_AGENT).retrieve().body(List.class);
      return extraireCoordonnees(resultats);
    } catch (Exception e) {
      log.warn("Nominatim géocodage échoué nom={} : {}", nom, e.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<Coordonnees> extraireCoordonnees(List<Map<String, Object>> resultats) {
    if (resultats == null || resultats.isEmpty()) return Optional.empty();
    Map<String, Object> premier = resultats.get(0);
    Object latBrut = premier.get("lat");
    Object lonBrut = premier.get("lon");
    if (latBrut == null || lonBrut == null) return Optional.empty();
    try {
      return Optional.of(
          new Coordonnees(
              Double.parseDouble(latBrut.toString()), Double.parseDouble(lonBrut.toString())));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
