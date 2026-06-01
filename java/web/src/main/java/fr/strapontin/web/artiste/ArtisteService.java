/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.Normalisation;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.admin.LogAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service de gestion des artistes suivis.
///
/// <p>Orchestration : recherche en base + API Deezer, vérification du cap, déclenchement du worker.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class ArtisteService {

  private static final Logger log = LoggerFactory.getLogger(ArtisteService.class);
  private static final String DEEZER_URL = "https://api.deezer.com/search/artist";
  private static final int CAP_ARTISTES = 500;

  private final ArtisteRepository artisteRepository;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final LogAdminService logAdminService;

  /// @param artisteRepository accès aux données artistes
  /// @param objectMapper      désérialiseur JSON (pour l'API Deezer)
  /// @param logAdminService   journalisation des actions admin
  public ArtisteService(
      ArtisteRepository artisteRepository,
      ObjectMapper objectMapper,
      LogAdminService logAdminService) {
    this.artisteRepository = artisteRepository;
    this.objectMapper = objectMapper;
    this.logAdminService = logAdminService;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /// Liste les artistes suivis, avec suggestions si {@code q} est fourni.
  ///
  /// <p>Complète la base avec l'API Deezer si moins de 3 résultats locaux.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param q         terme de recherche (optionnel, min 2 caractères)
  /// @return la liste des artistes et les suggestions
  public ReponseArtistes lister(String userId, String q) {
    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));

    List<ArtisteUtilisateur> artistes = artisteRepository.listerPourUtilisateur(userId);

    List<SuggestionArtiste> suggestions = List.of();
    boolean dejaLeSuivi = false;

    if (recherche.length() >= 2) {
      String rechercheNorm = Normalisation.normaliserTexte(recherche);
      Set<String> nomsSuivisNorm =
          artistes.stream().map(ArtisteUtilisateur::artisteNormalise).collect(Collectors.toSet());

      List<String> enBase = artisteRepository.rechercherEnBase("%" + rechercheNorm + "%");

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
                .collect(Collectors.toCollection(HashSet::new));
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

    return new ReponseArtistes(artistes, suggestions, dejaLeSuivi);
  }

  /// Ajoute un artiste à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Vérifie le doublon et le cap avant l'insertion.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param nom    nom de l'artiste (non-vide, max 200 car.)
  /// @throws RessourceDejaExistante si l'artiste est déjà suivi
  /// @throws ErreurMetier si le cap de 500 artistes est atteint
  @Transactional
  public void ajouter(String userId, String nom) {
    String normalise = Normalisation.normaliserTexte(nom);

    if (artisteRepository.existePourUtilisateur(userId, normalise)) {
      throw new RessourceDejaExistante("Cet artiste est déjà dans votre liste.");
    }
    if (artisteRepository.compterPourUtilisateur(userId) >= CAP_ARTISTES) {
      throw new ErreurMetier("Limite de 500 artistes atteinte.");
    }

    artisteRepository.ajouter(userId, normalise, nom);
    declencherWorkerArtiste();
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// @param userId           identifiant de l'utilisateur
  /// @param artisteNormalise nom normalisé de l'artiste
  public void supprimer(String userId, String artisteNormalise) {
    artisteRepository.supprimer(userId, artisteNormalise);
    log.info("artiste supprimé userId={} artiste={}", userId, artisteNormalise);
    logAdminService.logAdmin(
        "warn", "web", "artiste supprimé", Map.of("userId", userId, "artiste", artisteNormalise));
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (Exception e) {
      log.warn("Deezer recherche artiste échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private List<SuggestionArtiste> parseDeezerResultats(String json) {
    List<SuggestionArtiste> resultats = new ArrayList<>();
    try {
      Map<String, Object> root = objectMapper.readValue(json, Map.class);
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
    } catch (Exception _) {
      log.warn("Deezer parsing JSON échoué");
    }
    return resultats;
  }

  private void declencherWorkerArtiste() {
    httpClient
        .sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/trigger/artiste"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .exceptionally(
            e -> {
              log.warn("Déclenchement worker artiste impossible", e);
              return null;
            });
  }
}
