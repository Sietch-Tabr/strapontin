/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la gestion des salles suivies (portage de {@code salles/+page.server.ts}).
///
/// <p>Complète la recherche DB avec l'API Ticketmaster si moins de 3 résultats en base.
/// Déduplication fuzzy (distance de Levenshtein ≤ seuil proportionnel).
/// Limite à 200 salles par utilisateur.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/salles")
public class SalleController {

  private static final Logger log = LoggerFactory.getLogger(SalleController.class);
  private static final String TM_VENUES_URL =
      "https://app.ticketmaster.com/discovery/v2/venues.json";
  private static final int CAP_SALLES = 200;

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final HttpClient httpClient;

  public SalleController(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  record SalleUtilisateur(String salleId, String nom, String tmVenueId, Instant createdAt) {}

  record ResultatSalle(String salle, String ville, String tmVenueId, String source) {}

  record ReponseSalles(
      List<SalleUtilisateur> salles, List<ResultatSalle> resultats, boolean dejaLaSuivie) {}

  record RequeteAjouter(String nom, String tmVenueId) {}

  /// Liste les salles suivies, avec suggestions de recherche si {@code q} est fourni.
  ///
  /// @param q    terme de recherche (optionnel, min 2 caractères)
  /// @param auth contexte d'authentification
  /// @return la liste des salles et les suggestions
  @GetMapping
  public ResponseEntity<ReponseSalles> listerSalles(
      @RequestParam(required = false, defaultValue = "") String q, Authentication auth) {
    String userId = utilisateur(auth).id();
    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));

    List<SalleUtilisateur> salles =
        jdbc.query(
            """
            SELECT us.salle_id, s.nom, us.tm_venue_id, us.created_at
            FROM user_salles us
            JOIN salles s ON s.id = us.salle_id
            WHERE us.user_id = :userId
            ORDER BY lower(s.nom)
            """,
            Map.of("userId", userId),
            (rs, _) ->
                new SalleUtilisateur(
                    rs.getString("salle_id"),
                    rs.getString("nom"),
                    rs.getString("tm_venue_id"),
                    rs.getTimestamp("created_at").toInstant()));

    List<ResultatSalle> resultats = List.of();
    boolean dejaLaSuivie = false;

    if (recherche.length() >= 2) {
      Set<String> sallesDejaAjoutees =
          salles.stream().map(SalleUtilisateur::salleId).collect(Collectors.toSet());

      List<Map<String, Object>> enBase =
          jdbc.queryForList(
              """
              SELECT DISTINCT salle, ville FROM concerts
              WHERE salle ILIKE :pattern
              ORDER BY salle
              LIMIT 10
              """,
              Map.of("pattern", "%" + recherche + "%"));

      List<ResultatSalle> listeResultats =
          enBase.stream()
              .filter(
                  r -> !sallesDejaAjoutees.contains(normaliserSalleCle((String) r.get("salle"))))
              .map(
                  r ->
                      new ResultatSalle(
                          (String) r.get("salle"), (String) r.get("ville"), null, "base"))
              .collect(Collectors.toCollection(ArrayList::new));

      if (listeResultats.size() < 3) {
        List<ResultatSalle> tmResultats = rechercherSallesTM(recherche);
        List<String> sallesDejaPresentes =
            new ArrayList<>(
                listeResultats.stream().map(ResultatSalle::salle).collect(Collectors.toList()));
        for (String salleId : sallesDejaAjoutees) {
          sallesDejaPresentes.add(salleId);
        }

        for (ResultatSalle r : tmResultats) {
          if (sallesDejaPresentes.stream().noneMatch(s -> estMemeSalle(s, r.salle()))) {
            listeResultats.add(r);
            sallesDejaPresentes.add(r.salle());
          }
        }
      }

      resultats = listeResultats;
      dejaLaSuivie = salles.stream().anyMatch(s -> s.nom().equalsIgnoreCase(recherche));
    }

    return ResponseEntity.ok(new ReponseSalles(salles, resultats, dejaLaSuivie));
  }

  /// Ajoute une salle à la liste de suivi.
  ///
  /// @param requete corps JSON avec le nom et l'ID TM optionnel
  /// @param auth    contexte d'authentification
  /// @return 201 si créée, 400/409 en cas d'erreur
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterSalle(
      @RequestBody RequeteAjouter requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String nom = requete.nom() == null ? "" : requete.nom().strip();
    String tmVenueId =
        requete.tmVenueId() == null || requete.tmVenueId().isBlank()
            ? null
            : requete.tmVenueId().strip();

    if (nom.isEmpty() || nom.length() > 200) {
      return erreur(400, "Identifiant de salle invalide.");
    }

    String salleId = normaliserSalleCle(nom);
    if (salleId.isEmpty()) {
      return erreur(400, "Nom de salle invalide.");
    }

    boolean existeDeja =
        !jdbc.queryForList(
                """
                SELECT 1 FROM user_salles
                WHERE user_id = :userId AND salle_id = :salleId
                """,
                Map.of("userId", userId, "salleId", salleId),
                Integer.class)
            .isEmpty();

    if (existeDeja) {
      return erreur(409, "Cette salle est déjà dans votre liste.");
    }

    int total =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
                Map.of("userId", userId),
                Integer.class),
            0);

    if (total >= CAP_SALLES) {
      return erreur(400, "Limite de 200 salles atteinte.");
    }

    // Upsert dans salles (clé naturelle = salle_norm)
    Map<String, Object> paramsSalle = new HashMap<>();
    paramsSalle.put("id", salleId);
    paramsSalle.put("nom", nom);
    paramsSalle.put("nomNorm", salleId);
    jdbc.update(
        """
        INSERT INTO salles (id, nom, nom_norm)
        VALUES (:id, :nom, :nomNorm)
        ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom
        """,
        paramsSalle);

    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("salleId", salleId);
    params.put("tmVenueId", tmVenueId);
    jdbc.update(
        """
        INSERT INTO user_salles (user_id, salle_id, tm_venue_id)
        VALUES (:userId, :salleId, :tmVenueId)
        """,
        params);

    if (tmVenueId != null) {
      declencherWorkerSalle(tmVenueId);
    }
    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  private void declencherWorkerSalle(String venueId) {
    httpClient
        .sendAsync(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://localhost:8081/trigger/salle/"
                            + URLEncoder.encode(venueId, StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .exceptionally(
            e -> {
              log.warn("Déclenchement worker salle impossible venueId={}", venueId, e);
              return null;
            });
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param salleId identifiant normalisé de la salle
  /// @param auth    contexte d'authentification
  /// @return 204 si supprimée
  @DeleteMapping("/{salleId}")
  public ResponseEntity<Void> supprimerSalle(@PathVariable String salleId, Authentication auth) {
    String userId = utilisateur(auth).id();
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE user_id = :userId AND salle_id = :salleId
        """,
        Map.of("userId", userId, "salleId", salleId));
    return ResponseEntity.noContent().build();
  }

  // Normalise un nom de salle en retirant accents, ponctuation et espaces (clé stable)
  static String normaliserSalleCle(String nom) {
    String nfd = Normalizer.normalize(nom.toLowerCase(), Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "").replaceAll("[^a-z0-9]", "");
  }

  private static boolean estMemeSalle(String a, String b) {
    String na = normaliserSalleCle(a);
    String nb = normaliserSalleCle(b);
    if (na.equals(nb)) return true;
    int seuil = Math.max(2, (int) Math.floor(Math.min(na.length(), nb.length()) * 0.1));
    return levenshtein(na, nb) <= seuil;
  }

  private static int levenshtein(String a, String b) {
    int m = a.length(), n = b.length();
    int[] dp = new int[n + 1];
    for (int j = 0; j <= n; j++) dp[j] = j;
    for (int i = 1; i <= m; i++) {
      int prec = dp[0];
      dp[0] = i;
      for (int j = 1; j <= n; j++) {
        int tmp = dp[j];
        dp[j] =
            a.charAt(i - 1) == b.charAt(j - 1)
                ? prec
                : 1 + Math.min(prec, Math.min(dp[j], dp[j - 1]));
        prec = tmp;
      }
    }
    return dp[n];
  }

  @SuppressWarnings("unchecked")
  private List<ResultatSalle> rechercherSallesTM(String terme) {
    Optional<String> tmKey = configService.lireConfig("TICKETMASTER_API_KEY");
    if (tmKey.isEmpty()) return List.of();

    try {
      String url =
          TM_VENUES_URL
              + "?keyword="
              + URLEncoder.encode(terme, StandardCharsets.UTF_8)
              + "&countryCode=FR&size=6&apikey="
              + URLEncoder.encode(tmKey.get(), StandardCharsets.UTF_8);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return List.of();

      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> root = mapper.readValue(response.body(), Map.class);
      Map<String, Object> embedded = (Map<String, Object>) root.get("_embedded");
      if (embedded == null) return List.of();

      List<Map<String, Object>> venues = (List<Map<String, Object>>) embedded.get("venues");
      if (venues == null) return List.of();

      return venues.stream()
          .map(
              v -> {
                String nom = (String) v.get("name");
                String id = (String) v.get("id");
                Map<String, Object> city = (Map<String, Object>) v.get("city");
                String ville = city != null ? (String) city.get("name") : null;
                return new ResultatSalle(nom, ville, id, "ticketmaster");
              })
          .filter(r -> r.salle() != null)
          .collect(Collectors.toList());

    } catch (Exception e) {
      log.warn("TM recherche salle échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
