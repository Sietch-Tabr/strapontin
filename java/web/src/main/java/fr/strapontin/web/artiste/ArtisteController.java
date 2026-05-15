/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.artiste;

import fr.strapontin.core.Normalisation;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/// Contrôleur REST pour la gestion des artistes suivis (portage de {@code artistes/+page.server.ts}).
///
/// <p>Complète la recherche DB avec l'API Deezer si moins de 3 résultats en base.
/// Limite à 500 artistes par utilisateur.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/artistes")
public class ArtisteController {

  private static final Logger log = LoggerFactory.getLogger(ArtisteController.class);
  private static final String DEEZER_URL = "https://api.deezer.com/search/artist";
  private static final int CAP_ARTISTES = 500;

  private final NamedParameterJdbcTemplate jdbc;
  private final HttpClient httpClient;

  public ArtisteController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  record ArtisteUtilisateur(String artisteNormalise, String artisteAffiche, Instant createdAt) {}

  record SuggestionArtiste(String nom, String source) {}

  record ReponseArtistes(
      List<ArtisteUtilisateur> artistes,
      List<SuggestionArtiste> suggestions,
      boolean dejaLeSuivi) {}

  record RequeteAjouter(String nom) {}

  /// Liste les artistes suivis, avec suggestions de recherche si {@code q} est fourni.
  ///
  /// @param q      terme de recherche (optionnel, min 2 caractères)
  /// @param auth   contexte d'authentification Spring Security
  /// @return la liste des artistes et les suggestions
  @GetMapping
  public ResponseEntity<ReponseArtistes> listerArtistes(
      @RequestParam(required = false, defaultValue = "") String q, Authentication auth) {
    String userId = utilisateur(auth).id();
    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));

    List<ArtisteUtilisateur> artistes =
        jdbc.query(
            """
            SELECT artiste_normalise, artiste_affiche, created_at
            FROM user_artistes
            WHERE user_id = :userId
            ORDER BY lower(artiste_affiche)
            """,
            Map.of("userId", userId),
            (rs, _) ->
                new ArtisteUtilisateur(
                    rs.getString("artiste_normalise"),
                    rs.getString("artiste_affiche"),
                    rs.getTimestamp("created_at").toInstant()));

    List<SuggestionArtiste> suggestions = List.of();
    boolean dejaLeSuivi = false;

    if (recherche.length() >= 2) {
      String rechercheNorm = Normalisation.normaliserTexte(recherche);
      Set<String> nomsSuivisNorm =
          artistes.stream().map(ArtisteUtilisateur::artisteNormalise).collect(Collectors.toSet());

      List<String> enBase =
          jdbc.queryForList(
              """
              SELECT DISTINCT artiste FROM concerts
              WHERE artiste_norm LIKE :pattern
              ORDER BY artiste
              LIMIT 5
              """,
              Map.of("pattern", "%" + rechercheNorm + "%"),
              String.class);

      List<SuggestionArtiste> resultatsSuggestions =
          enBase.stream()
              .filter(a -> !nomsSuivisNorm.contains(Normalisation.normaliserTexte(a)))
              .map(a -> new SuggestionArtiste(a, "base"))
              .collect(Collectors.toCollection(ArrayList::new));

      if (resultatsSuggestions.size() < 3) {
        List<SuggestionArtiste> deezer = rechercherDeezer(recherche);
        Set<String> nomsDejaPresents =
            resultatsSuggestions.stream()
                .map(s -> Normalisation.normaliserTexte(s.nom()))
                .collect(Collectors.toCollection(java.util.HashSet::new));
        nomsDejaPresents.addAll(nomsSuivisNorm);

        for (SuggestionArtiste r : deezer) {
          String norm = Normalisation.normaliserTexte(r.nom());
          if (!nomsDejaPresents.contains(norm)) {
            resultatsSuggestions.add(r);
            nomsDejaPresents.add(norm);
          }
        }
      }

      suggestions = resultatsSuggestions;
      dejaLeSuivi = nomsSuivisNorm.contains(Normalisation.normaliserTexte(recherche));
    }

    return ResponseEntity.ok(new ReponseArtistes(artistes, suggestions, dejaLeSuivi));
  }

  /// Ajoute un artiste à la liste de suivi de l'utilisateur.
  ///
  /// @param requete corps JSON avec le nom de l'artiste
  /// @param auth    contexte d'authentification
  /// @return 201 si créé, 400/409 en cas d'erreur
  @PostMapping
  public ResponseEntity<Map<String, Object>> ajouterArtiste(
      @RequestBody RequeteAjouter requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String nom = requete.nom() == null ? "" : requete.nom().strip();

    if (nom.isEmpty()) {
      return erreur(400, "Le nom de l'artiste est obligatoire.");
    }
    if (nom.length() > 200) {
      return erreur(400, "Nom trop long (200 caractères max).");
    }

    String normalise = Normalisation.normaliserTexte(nom);

    boolean existeDeja =
        !jdbc.queryForList(
                """
                SELECT 1 FROM user_artistes
                WHERE user_id = :userId AND artiste_normalise = :normalise
                """,
                Map.of("userId", userId, "normalise", normalise),
                Integer.class)
            .isEmpty();

    if (existeDeja) {
      return erreur(409, "Cet artiste est déjà dans votre liste.");
    }

    int total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);

    if (total >= CAP_ARTISTES) {
      return erreur(400, "Limite de 500 artistes atteinte.");
    }

    jdbc.update(
        """
        INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche)
        VALUES (:userId, :normalise, :nom)
        """,
        Map.of("userId", userId, "normalise", normalise, "nom", nom));

    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// @param artisteNormalise l'artiste normalisé à supprimer
  /// @param auth             contexte d'authentification
  /// @return 204 si supprimé
  @DeleteMapping("/{artisteNormalise}")
  public ResponseEntity<Void> supprimerArtiste(
      @PathVariable String artisteNormalise, Authentication auth) {
    String userId = utilisateur(auth).id();
    jdbc.update(
        """
        DELETE FROM user_artistes
        WHERE user_id = :userId AND artiste_normalise = :artisteNormalise
        """,
        Map.of("userId", userId, "artisteNormalise", artisteNormalise));
    return ResponseEntity.noContent().build();
  }

  private List<SuggestionArtiste> rechercherDeezer(String terme) {
    try {
      String url =
          DEEZER_URL + "?q=" + URLEncoder.encode(terme, StandardCharsets.UTF_8) + "&limit=6";
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Deezer erreur HTTP {} pour '{}'", response.statusCode(), terme);
        return List.of();
      }
      return parseDeezerResultats(response.body());
    } catch (Exception e) {
      log.warn("Deezer recherche artiste échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private List<SuggestionArtiste> parseDeezerResultats(String json) {
    // Parsing JSON minimal sans dépendance Jackson directe
    // Format attendu : {"data":[{"name":"...","nb_fan":...},...]}
    List<SuggestionArtiste> resultats = new ArrayList<>();
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> root = mapper.readValue(json, Map.class);
      List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
      if (data == null) return resultats;

      data.stream()
          .sorted(
              (a, b) ->
                  Integer.compare(
                      ((Number) b.getOrDefault("nb_fan", 0)).intValue(),
                      ((Number) a.getOrDefault("nb_fan", 0)).intValue()))
          .map(a -> (String) a.get("name"))
          .filter(name -> name != null && !name.isBlank())
          .distinct()
          .forEach(name -> resultats.add(new SuggestionArtiste(name, "deezer")));
    } catch (Exception e) {
      log.warn("Deezer parsing JSON échoué", e);
    }
    return resultats;
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
