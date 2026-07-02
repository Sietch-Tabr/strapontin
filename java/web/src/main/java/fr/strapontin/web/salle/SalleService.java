/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.TicketmasterSource;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
  private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
  // Types Nominatim pertinents pour une salle de spectacle (#718, #779) — testés sur le type de
  // premier niveau ET sur extratags.amenity (une salle taguée leisure/tourism, ex. un stade, peut
  // porter amenity en tag secondaire). community_centre couvre les salles des fêtes/polyvalentes
  // (petites communes, souvent seule couverture Digitick disponible).
  private static final Set<String> TYPES_SALLE_NOMINATIM =
      Set.of(
          "music_venue",
          "arts_centre",
          "theatre",
          "nightclub",
          "events_venue",
          "concert_hall",
          "community_centre");
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
  /// <p>Complète la base avec Nominatim (#718) si moins de 3 résultats locaux, puis avec l'API
  /// Ticketmaster si Nominatim retourne moins de 2 résultats. Une salle retournée par les deux
  /// sources externes est fusionnée en une seule suggestion (osmId + tmVenueId).
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
                  r -> {
                    String digitickSlug = (String) r.get("digitick_slug");
                    String src = digitickSlug != null ? "base-digitick" : "base";
                    return new ResultatSalle(
                        (String) r.get("salle"), (String) r.get("ville"), null, digitickSlug, src);
                  })
              .collect(Collectors.toCollection(ArrayList::new));

      if (listeResultats.size() < 3) {
        List<ResultatSalle> resultatsExternes =
            new ArrayList<>(rechercherSallesNominatim(recherche));
        if (resultatsExternes.size() < 2) {
          resultatsExternes.addAll(rechercherSallesTM(recherche));
        }
        for (ResultatSalle r : resultatsExternes) {
          if (sallesDejaAjoutees.contains(normaliserSalleCle(r.salle()))) continue;
          fusionnerOuAjouter(listeResultats, r);
        }
      }

      resultats = listeResultats;
      String rechercheNorm = normaliserSalleCle(recherche);
      dejaLaSuivie = contientCorrespondancePartielle(rechercheNorm, sallesDejaAjoutees);
    }

    return new ReponseSalles(salles, resultats, dejaLaSuivie);
  }

  /// Fusionne un candidat externe avec une suggestion déjà présente désignant la même salle
  /// ({@link #estMemeSalle}), ou l'ajoute comme nouvelle suggestion sinon.
  ///
  /// <p>Permet à Nominatim et Ticketmaster de contribuer chacun leurs identifiants (osmId,
  /// tmVenueId) à une même suggestion plutôt que de créer deux entrées dupliquées (#718).
  ///
  /// <p>Package-private (plutôt que {@code private}) pour être testable directement.
  ///
  /// @param liste    suggestions déjà collectées (modifiée en place)
  /// @param candidat nouveau résultat externe à intégrer
  static void fusionnerOuAjouter(List<ResultatSalle> liste, ResultatSalle candidat) {
    for (int i = 0; i < liste.size(); i++) {
      ResultatSalle existant = liste.get(i);
      if (estMemeSalle(existant.salle(), candidat.salle())) {
        liste.set(
            i,
            new ResultatSalle(
                existant.salle(),
                existant.ville() != null ? existant.ville() : candidat.ville(),
                existant.tmVenueId() != null ? existant.tmVenueId() : candidat.tmVenueId(),
                existant.digitickSlug() != null ? existant.digitickSlug() : candidat.digitickSlug(),
                existant.source(),
                existant.osmId() != null ? existant.osmId() : candidat.osmId(),
                existant.wikidataId() != null ? existant.wikidataId() : candidat.wikidataId()));
        return;
      }
    }
    liste.add(candidat);
  }

  /// Ajoute une salle à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Normalise le nom, vérifie le doublon et le cap, puis crée la salle et la relation. La
  /// résolution de la salle dans le référentiel global tolère les variantes orthographiques
  /// mineures (voir {@link #resoudreSalleUuid}) pour éviter de créer un doublon d'UUID pour une
  /// salle déjà présente sous une graphie légèrement différente.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param nom       nom de la salle (non-vide, max 200 car.)
  /// @param tmVenueId identifiant Ticketmaster (nullable)
  /// @throws ErreurMetier          si le nom normalisé est vide ou le cap de 200 salles atteint
  /// @throws RessourceDejaExistante si la salle est déjà suivie
  @Transactional
  public void ajouter(String userId, String nom, String tmVenueId) {
    ajouter(userId, nom, tmVenueId, null, null);
  }

  /// Ajoute une salle à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Normalise le nom, vérifie le doublon et le cap, puis crée la salle et la relation. La
  /// résolution de la salle dans le référentiel global tolère les variantes orthographiques
  /// mineures (voir {@link #resoudreSalleUuid}) pour éviter de créer un doublon d'UUID pour une
  /// salle déjà présente sous une graphie légèrement différente.
  ///
  /// <p>Si {@code osmId}/{@code wikidataId} sont fournis (l'utilisateur a choisi une suggestion
  /// Nominatim, #718), les IDs stables sont persistés immédiatement sans attendre le job
  /// d'enrichissement nocturne (#706). Un conflit sur l'index unique {@code
  /// idx_salles_wikidata_id} (Q-ID déjà porté par une autre salle) est rattrapé et loggé plutôt
  /// que de faire échouer l'ajout — même pattern que {@code EnrichissementSallesService} (jalon
  /// 2.6.0).
  ///
  /// @param userId     identifiant de l'utilisateur
  /// @param nom        nom de la salle (non-vide, max 200 car.)
  /// @param tmVenueId  identifiant Ticketmaster (nullable)
  /// @param osmId      identifiant OSM {@code "type/id"} (nullable)
  /// @param wikidataId Q-ID Wikidata (nullable)
  /// @throws ErreurMetier          si le nom normalisé est vide ou le cap de 200 salles atteint
  /// @throws RessourceDejaExistante si la salle est déjà suivie
  @Transactional
  public void ajouter(
      String userId, String nom, String tmVenueId, String osmId, String wikidataId) {
    String salleCle = normaliserSalleCle(nom);
    if (salleCle.isEmpty()) {
      throw new ErreurMetier("Nom de salle invalide.");
    }

    UUID salleUuid = resoudreSalleUuid(nom, salleCle);
    if (salleRepository.existePourUtilisateur(userId, salleUuid)) {
      throw new RessourceDejaExistante("Cette salle est déjà dans votre liste.");
    }
    if (salleRepository.compterPourUtilisateur(userId) >= CAP_SALLES) {
      throw new ErreurMetier("Limite de 200 salles atteinte.");
    }

    if (tmVenueId != null) {
      salleRepository.enregistrerSalleSource(salleUuid, "ticketmaster", tmVenueId);
    }
    if (osmId != null && !osmId.isBlank()) {
      salleRepository.enregistrerSalleSource(salleUuid, "osm", osmId);
    }
    if (wikidataId != null && !wikidataId.isBlank()) {
      try {
        salleRepository.mettreAJourWikidataId(salleUuid, wikidataId);
      } catch (DuplicateKeyException e) {
        log.warn(
            "wikidataId déjà attribué à une autre salle — conflit ignoré salle={} wikidataId={}",
            nom,
            wikidataId);
      }
    }
    salleRepository.ajouterPourUtilisateur(userId, salleUuid);

    declencherWorkerParSources(salleUuid);
  }

  /// Résout l'UUID d'une salle dans le référentiel global, en réutilisant une salle existante
  /// dont le nom est un doublon flou plutôt que d'en créer une nouvelle. La comparaison floue
  /// utilise {@link CorrespondanceSalle#estDoublonSalle} sur le nom normalisé au sens de
  /// {@link Normalisation#normaliserTexte} (même algorithme que {@code ReconciliateurSalles} côté
  /// worker et {@code WikidataSalleSource} côté enrichissement) plutôt que {@link #estMemeSalle} —
  /// {@code salle_cle} concatène tous les caractères alphanumériques sans espace, ce qui rend le
  /// Jaccard sur tokens inopérant ; {@code nom} conserve les espaces nécessaires à la
  /// tokenisation. En cas de correspondance exacte de {@code salle_cle}, passe par
  /// {@code upsertSalle} pour conserver le comportement existant de mise à jour du nom affiché ;
  /// en cas de correspondance floue seulement, réutilise l'UUID existant sans toucher au nom déjà
  /// établi.
  ///
  /// @param nom      nom affiché tel que saisi
  /// @param salleCle clé normalisée ({@link #normaliserSalleCle})
  /// @return UUID de la salle existante ou nouvellement créée
  private UUID resoudreSalleUuid(String nom, String salleCle) {
    Set<String> tokens =
        Arrays.stream(nom.split("\\s+"))
            .map(SalleService::normaliserSalleCle)
            .filter(t -> t.length() >= 3)
            .collect(Collectors.toSet());
    String nomNormCible = Normalisation.normaliserTexte(nom);
    for (Map<String, Object> candidat : salleRepository.chercherCandidatsParCle(tokens)) {
      String cleCandidate = (String) candidat.get("salle_cle");
      if (cleCandidate.equals(salleCle)) {
        return salleRepository.upsertSalle(salleCle, nom);
      }
      String nomCandidat = (String) candidat.get("nom");
      if (CorrespondanceSalle.estDoublonSalle(
          nomNormCible, Normalisation.normaliserTexte(nomCandidat))) {
        return (UUID) candidat.get("id");
      }
    }
    return salleRepository.upsertSalle(salleCle, nom);
  }

  /// Déclenche immédiatement un fetch pour une salle identifiée par son UUID.
  ///
  /// <p>Lit toutes les sources configurées dans {@code salle_sources} (TM, Digitick…)
  /// et les transmet au worker en une seule requête.
  ///
  /// @param salleUuid UUID de la salle
  public void declencherWorkerParSources(UUID salleUuid) {
    String identifiants = salleRepository.lireIdentifiantsSources(salleUuid);
    if (identifiants != null) {
      declencherWorkerSalle(identifiants);
    }
  }

  /// Supprime une salle de la liste de suivi.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleUuid UUID stable de la salle
  public void supprimer(String userId, UUID salleUuid) {
    salleRepository.supprimerPourUtilisateur(userId, salleUuid);
  }

  /// Crée une salle Digitick dans le référentiel et déclenche un fetch immédiat.
  ///
  /// <p>Upsert sur {@code salle_cle}, enregistre le slug dans {@code salle_sources},
  /// puis déclenche le worker.
  ///
  /// @param nom   nom affiché de la salle
  /// @param slug  slug Digitick
  /// @param ville ville (peut être {@code null})
  /// @param dept  numéro de département (peut être {@code null})
  /// @return UUID stable de la salle créée ou retrouvée
  @Transactional
  public UUID creerSalleDigitick(String nom, String slug, String ville, String dept) {
    String salleCle = normaliserSalleCle(nom);
    UUID salleUuid = salleRepository.upsertSalleAvecLoc(salleCle, nom, ville, dept);
    salleRepository.enregistrerSalleSource(salleUuid, "digitick", slug);
    declencherWorkerParSources(salleUuid);
    return salleUuid;
  }

  /// Met à jour les métadonnées d'une salle (nom, ville, département).
  ///
  /// @param salleUuid UUID de la salle
  /// @param nom       nouveau nom (null = inchangé)
  /// @param ville     nouvelle ville (null = inchangée)
  /// @param dept      nouveau numéro de département (null = inchangé)
  public void mettreAJourSalle(UUID salleUuid, String nom, String ville, String dept) {
    salleRepository.mettreAJourSalle(salleUuid, nom, ville, dept);
  }

  /// Retourne les identifiants sources CSV d'une salle (ex. {@code
  // "digitick:lempreinte,tm:rZ..."}).
  ///
  /// @param salleUuid UUID de la salle
  /// @return CSV d'identifiants ou {@code null} si aucun
  public String lireIdentifiantsSources(UUID salleUuid) {
    return salleRepository.lireIdentifiantsSources(salleUuid);
  }

  /// Supprime une salle Digitick si elle n'est suivie par aucun utilisateur et sans concerts.
  ///
  /// @param salleUuid UUID de la salle
  /// @return {@code true} si supprimée, {@code false} si des contraintes bloquent
  public boolean supprimerSalleDigitick(UUID salleUuid) {
    return salleRepository.supprimerSalleDigitick(salleUuid);
  }

  /// Normalise un nom de salle en retirant accents, ponctuation et espaces (clé stable).
  ///
  /// @param nom nom brut de la salle
  /// @return identifiant normalisé (ex. {@code "olympia"})
  public static String normaliserSalleCle(String nom) {
    String nfd = Normalizer.normalize(nom.toLowerCase(), Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "").replaceAll("[^a-z0-9]", "");
  }

  /// Indique si une recherche correspond, même partiellement, à une salle déjà suivie (#780).
  ///
  /// <p>Une égalité stricte laissait passer un faux "Aucun résultat" quand la recherche est un
  /// terme partiel (ex. "Zenith" ne matchait pas "zenithparis") alors que la salle a bien été
  /// trouvée puis exclue des suggestions car déjà suivie — l'utilisateur n'avait alors aucun
  /// signal expliquant l'absence de résultat.
  ///
  /// <p>Package-private (plutôt que {@code private}) pour être testable directement.
  ///
  /// @param rechercheNorm clé normalisée de la recherche ({@link #normaliserSalleCle})
  /// @param clesNorm      clés normalisées des salles déjà suivies
  /// @return {@code true} si une salle déjà suivie contient la recherche
  static boolean contientCorrespondancePartielle(String rechercheNorm, Set<String> clesNorm) {
    return clesNorm.stream().anyMatch(cle -> cle.contains(rechercheNorm));
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
  /// Recherche des salles par nom via Nominatim (OSM, #718).
  ///
  /// <p>Sans clé API — couvre les salles hors référentiel Ticketmaster. User-Agent obligatoire
  /// selon la politique d'usage Nominatim.
  ///
  /// @param terme terme de recherche saisi par l'utilisateur
  /// @return suggestions filtrées par type de lieu pertinent, vide si aucune ou en cas d'échec
  ///     réseau
  private List<ResultatSalle> rechercherSallesNominatim(String terme) {
    try {
      String contact = configService.lireConfig("CONTACT_EMAIL").orElse("contact@strapontin.fr");
      String url =
          NOMINATIM_URL
              + "?q="
              + URLEncoder.encode(terme, StandardCharsets.UTF_8)
              + "&format=json&limit=6&extratags=1&addressdetails=1&countrycodes=fr";
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("User-Agent", "Strapontin/2.6.2 (contact: " + contact + ")")
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Nominatim erreur HTTP {} pour '{}'", response.statusCode(), terme);
        return List.of();
      }
      return parseNominatimResultats(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (Exception e) {
      log.warn("Nominatim recherche salle échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  /// Filtre et convertit les résultats bruts Nominatim en suggestions de salle (#718, #779).
  ///
  /// <p>Un résultat est pertinent si son {@code type} de premier niveau ou son
  /// {@code extratags.amenity} (tag secondaire) figure dans {@link #TYPES_SALLE_NOMINATIM} —
  /// {@code class=amenity} seul n'est plus suffisant (trop large : laissait passer des bornes de
  /// recharge électrique) et une salle classée sous {@code leisure}/{@code tourism} (ex. un
  /// stade avec {@code extratags.amenity=music_venue}) n'est plus rejetée à tort.
  ///
  /// <p>Package-private (plutôt que {@code private}) pour être testable directement, sans appel
  /// réseau réel.
  ///
  /// @param json corps de la réponse Nominatim (tableau JSON)
  /// @return suggestions filtrées par pertinence, vide si aucune ou en cas d'échec de parsing
  @SuppressWarnings("unchecked")
  List<ResultatSalle> parseNominatimResultats(String json) {
    List<ResultatSalle> resultats = new ArrayList<>();
    try {
      List<Map<String, Object>> items = objectMapper.readValue(json, List.class);
      for (Map<String, Object> item : items) {
        String type = (String) item.get("type");
        Map<String, Object> extratags = (Map<String, Object>) item.get("extratags");
        String amenitySecondaire = extratags != null ? (String) extratags.get("amenity") : null;
        boolean pertinent =
            TYPES_SALLE_NOMINATIM.contains(type)
                || (amenitySecondaire != null && TYPES_SALLE_NOMINATIM.contains(amenitySecondaire));
        if (!pertinent) continue;

        String displayName = (String) item.get("display_name");
        if (displayName == null || displayName.isBlank()) continue;
        String nom = displayName.split(",")[0].strip();

        String osmType = (String) item.get("osm_type");
        Object osmIdBrut = item.get("osm_id");
        String osmId = osmType != null && osmIdBrut != null ? osmType + "/" + osmIdBrut : null;

        Map<String, Object> address = (Map<String, Object>) item.get("address");
        String ville = extraireVille(address);

        String wikidataId = extratags != null ? (String) extratags.get("wikidata") : null;

        resultats.add(new ResultatSalle(nom, ville, null, null, "osm", osmId, wikidataId));
      }
    } catch (Exception e) {
      log.warn("Nominatim parsing JSON échoué", e);
    }
    return resultats;
  }

  private static String extraireVille(Map<String, Object> address) {
    if (address == null) return null;
    for (String cle : List.of("city", "town", "village", "municipality")) {
      Object valeur = address.get(cle);
      if (valeur instanceof String s && !s.isBlank()) return s;
    }
    return null;
  }

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
                String nomBrut = (String) v.get("name");
                if (nomBrut == null)
                  return new ResultatSalle(null, null, null, null, "ticketmaster");
                String nom = TicketmasterSource.normaliserCasse(nomBrut);
                if (nomBrut.contains("??")) {
                  log.warn("TM salle nom corrompu id={} nom='{}'", v.get("id"), nomBrut);
                }
                String id = (String) v.get("id");
                Map<String, Object> city = (Map<String, Object>) v.get("city");
                String ville = city != null ? (String) city.get("name") : null;
                return new ResultatSalle(nom, ville, id, null, "ticketmaster");
              })
          .filter(r -> r.salle() != null)
          .collect(Collectors.toList());

    } catch (Exception e) {
      log.warn("TM recherche salle échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  private void declencherWorkerSalle(String identifiants) {
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
