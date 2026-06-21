/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.config.ConfigService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service de gestion des salles suivies.
///
/// <p>Orchestration : recherche en base + API Ticketmaster, vérification du cap,
/// déclenchement du worker.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class SalleService {

  private static final Logger log = LoggerFactory.getLogger(SalleService.class);
  private static final String TM_VENUES_URL =
      "https://app.ticketmaster.com/discovery/v2/venues.json";
  private static final int CAP_SALLES = 200;

  private final SalleRepository salleRepository;
  private final ConfigService configService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /// @param salleRepository accès aux données salles
  /// @param configService   lecture de la clé API Ticketmaster
  /// @param objectMapper    désérialiseur JSON (pour l'API Ticketmaster)
  public SalleService(
      SalleRepository salleRepository, ConfigService configService, ObjectMapper objectMapper) {
    this.salleRepository = salleRepository;
    this.configService = configService;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  /// Liste les salles suivies, avec suggestions si {@code q} est fourni.
  ///
  /// <p>Complète la base avec l'API Ticketmaster si moins de 3 résultats locaux.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param q      terme de recherche (optionnel, min 2 caractères)
  /// @return la liste des salles et les suggestions
  public ReponseSalles lister(String userId, String q) {
    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));

    List<SalleUtilisateur> salles = salleRepository.listerPourUtilisateur(userId);

    List<ResultatSalle> resultats = List.of();
    boolean dejaLaSuivie = false;

    if (recherche.length() >= 2) {
      Set<String> sallesDejaAjoutees =
          salles.stream().map(s -> normaliserSalleCle(s.nom())).collect(Collectors.toSet());

      List<Map<String, Object>> enBase =
          salleRepository.rechercherEnBase("%" + normaliserSalleCle(recherche) + "%");

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
        sallesDejaPresentes.addAll(sallesDejaAjoutees);

        for (ResultatSalle r : tmResultats) {
          if (sallesDejaPresentes.stream().noneMatch(s -> estMemeSalle(s, r.salle()))) {
            listeResultats.add(r);
            sallesDejaPresentes.add(r.salle());
          }
        }
      }

      resultats = listeResultats;
      String rechercheNorm = normaliserSalleCle(recherche);
      dejaLaSuivie =
          salles.stream().anyMatch(s -> normaliserSalleCle(s.nom()).equals(rechercheNorm));
    }

    return new ReponseSalles(salles, resultats, dejaLaSuivie);
  }

  /// Ajoute une salle à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Normalise le nom, vérifie le doublon et le cap, puis crée la salle et la relation.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param nom       nom de la salle (non-vide, max 200 car.)
  /// @param tmVenueId identifiant Ticketmaster (nullable)
  /// @throws ErreurMetier          si le nom normalisé est vide ou le cap de 200 salles atteint
  /// @throws RessourceDejaExistante si la salle est déjà suivie
  @Transactional
  public void ajouter(String userId, String nom, String tmVenueId) {
    String salleCle = normaliserSalleCle(nom);
    if (salleCle.isEmpty()) {
      throw new ErreurMetier("Nom de salle invalide.");
    }

    UUID salleUuid = salleRepository.upsertSalle(salleCle, nom);
    if (salleRepository.existePourUtilisateur(userId, salleUuid)) {
      throw new RessourceDejaExistante("Cette salle est déjà dans votre liste.");
    }
    if (salleRepository.compterPourUtilisateur(userId) >= CAP_SALLES) {
      throw new ErreurMetier("Limite de 200 salles atteinte.");
    }

    if (tmVenueId != null) {
      salleRepository.enregistrerSalleSource(salleUuid, "ticketmaster", tmVenueId);
    }
    salleRepository.ajouterPourUtilisateur(userId, salleUuid);

    if (tmVenueId != null) {
      declencherWorkerSalle(tmVenueId);
    }
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleUuid UUID stable de la salle
  public void supprimer(String userId, UUID salleUuid) {
    salleRepository.supprimerPourUtilisateur(userId, salleUuid);
  }

  /// Normalise un nom de salle en retirant accents, ponctuation et espaces (clé stable).
  ///
  /// @param nom nom brut de la salle
  /// @return identifiant normalisé (ex. {@code "olympia"})
  public static String normaliserSalleCle(String nom) {
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

      Map<String, Object> root = objectMapper.readValue(response.body(), Map.class);
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

  private void declencherWorkerSalle(String venueId) {
    String identifiants = "tm:" + venueId;
    httpClient
        .sendAsync(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://localhost:8081/trigger/salle?identifiants="
                            + URLEncoder.encode(identifiants, StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .exceptionally(
            e -> {
              log.warn("Déclenchement worker salle impossible identifiants={}", identifiants, e);
              return null;
            });
  }
}
