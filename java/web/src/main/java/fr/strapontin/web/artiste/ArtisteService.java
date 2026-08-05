/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.FiltreArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.admin.LogAdminService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

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
  private static final String WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php";
  private static final String WIKIDATA_USER_AGENT =
      "Strapontin/2.6.3 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr)";
  // Mots-clés (description Wikidata, fr/en) distinguant un artiste musical ou un humoriste
  // des homonymes non pertinents pour l'autocomplete.
  private static final Pattern MOTS_CLES_ARTISTE =
      Pattern.compile(
          "chanteur|chanteuse|musicien|musicienne|rappeur|rappeuse|compositeur|compositrice"
              + "|groupe|humoriste|dj\\b"
              + "|singer|rapper|musician|band|composer|songwriter|comedian|humorist",
          Pattern.CASE_INSENSITIVE);
  private static final int CAP_ARTISTES = 500;

  private final ArtisteRepository artisteRepository;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final LogAdminService logAdminService;

  /// @param artisteRepository accès aux données artistes
  /// @param objectMapper désérialiseur JSON (pour l'API Deezer)
  /// @param restClient client HTTP partagé (timeout 5s/10s défini dans {@link
  ///   fr.strapontin.web.WebConfiguration})
  /// @param logAdminService journalisation des actions admin
  public ArtisteService(
      ArtisteRepository artisteRepository,
      ObjectMapper objectMapper,
      RestClient restClient,
      LogAdminService logAdminService) {
    this.artisteRepository = artisteRepository;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.logAdminService = logAdminService;
  }

  /// Liste les artistes suivis, avec suggestions si {@code q} est fourni.
  ///
  /// <p>Complète la base avec Wikidata Search puis, si toujours moins de 3 résultats, avec l'API
  /// Deezer (comportement historique conservé en dernier repli — utile pour les artistes émergents
  /// sans page Wikidata).
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param q terme de recherche (optionnel, min 2 caractères)
  /// @return la liste des artistes et les suggestions
  public ReponseArtistes lister(String userId, String q) {
    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));

    List<ArtisteUtilisateur> artistes = artisteRepository.listerPourUtilisateur(userId);

    List<SuggestionArtiste> suggestions = List.of();
    boolean dejaLeSuivi = false;

    if (recherche.length() >= 2) {
      String rechercheNorm = Normalisation.normaliserTexte(recherche);
      Set<String> nomsSuivisNorm =
          artistes.stream()
              .map(a -> Normalisation.normaliserTexte(a.artisteAffiche()))
              .collect(Collectors.toSet());

      List<String> enBase = artisteRepository.rechercherEnBase("%" + rechercheNorm + "%");

      List<SuggestionArtiste> resultatsSuggestions =
          enBase.stream()
              .filter(a -> !nomsSuivisNorm.contains(Normalisation.normaliserTexte(a)))
              .map(a -> new SuggestionArtiste(a, "base"))
              .collect(Collectors.toCollection(ArrayList::new));

      Set<String> nomsDejaPresents =
          resultatsSuggestions.stream()
              .map(s -> Normalisation.normaliserTexte(s.nom()))
              .collect(Collectors.toCollection(HashSet::new));
      nomsDejaPresents.addAll(nomsSuivisNorm);

      if (resultatsSuggestions.size() < 3) {
        ajouterSuggestionsInedites(
            resultatsSuggestions, nomsDejaPresents, rechercherWikidata(recherche));
      }
      if (resultatsSuggestions.size() < 3) {
        ajouterSuggestionsInedites(
            resultatsSuggestions, nomsDejaPresents, rechercherDeezer(recherche));
      }

      suggestions = resultatsSuggestions;
      dejaLeSuivi = contientCorrespondancePartielle(rechercheNorm, nomsSuivisNorm);
    }

    return new ReponseArtistes(artistes, suggestions, dejaLeSuivi);
  }

  private static void ajouterSuggestionsInedites(
      List<SuggestionArtiste> cible, Set<String> nomsDejaPresents, List<SuggestionArtiste> source) {
    for (SuggestionArtiste r : source) {
      String norm = Normalisation.normaliserTexte(r.nom());
      if (!nomsDejaPresents.contains(norm)) {
        cible.add(r);
        nomsDejaPresents.add(norm);
      }
    }
  }

  /// Ajoute un artiste à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Vérifie le doublon et le cap avant l'insertion. La résolution de l'artiste dans le
  /// référentiel global tolère les variantes orthographiques mineures (voir {@link
  /// #resoudreArtisteId}) pour éviter de créer un doublon d'UUID pour un artiste déjà présent sous
  /// une graphie légèrement différente (ex. "Krash Karma" / "KrashKarma" — cas réel ayant nécessité
  /// une fusion manuelle en base avant ce correctif, v2.6.0).
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param nom nom de l'artiste (non-vide, max 200 car.)
  /// @throws RessourceDejaExistante si l'artiste est déjà suivi
  /// @throws ErreurMetier si le nom est un tribute/hommage ou si le cap de 500 artistes est atteint
  @Transactional
  public void ajouter(String userId, String nom) {
    ajouter(userId, nom, null);
  }

  /// Ajoute un artiste à la liste de suivi dans une transaction atomique.
  ///
  /// <p>Vérifie le doublon et le cap avant l'insertion. La résolution de l'artiste dans le
  /// référentiel global tolère les variantes orthographiques mineures (voir {@link
  /// #resoudreArtisteId}) pour éviter de créer un doublon d'UUID pour un artiste déjà présent sous
  /// une graphie légèrement différente (ex. "Krash Karma" / "KrashKarma" — cas réel ayant nécessité
  /// une fusion manuelle en base avant ce correctif, v2.6.0).
  ///
  /// <p>Si {@code wikidataId} est fourni (l'utilisateur a choisi une suggestion Wikidata plutôt que
  /// de saisir un nom libre), résout le P2722 (Deezer ID) via {@code wbgetentities} et persiste les
  /// IDs stables immédiatement, sans attendre le job d'enrichissement nocturne. Un conflit sur
  /// l'index unique {@code idx_artistes_wikidata_id} (Q-ID déjà porté par un autre artiste) est
  /// rattrapé et loggé plutôt que de faire échouer l'ajout — même pattern que {@code
  /// EnrichissementArtistesService} (jalon 2.6.0) : mieux vaut suivre l'artiste sans son Q-ID que
  /// ne pas pouvoir le suivre du tout.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param nom nom de l'artiste (non-vide, max 200 car.)
  /// @param wikidataId Q-ID Wikidata optionnel (peut être {@code null})
  /// @throws RessourceDejaExistante si l'artiste est déjà suivi
  /// @throws ErreurMetier si le nom est un tribute/hommage ou si le cap de 500 artistes est atteint
  @Transactional
  public void ajouter(String userId, String nom, String wikidataId) {
    if (FiltreArtiste.estTribute(nom)) {
      throw new ErreurMetier(
          "Ce nom ressemble à un hommage/tribute — suivez l'artiste original plutôt que"
              + " l'attraction dédiée.");
    }
    String normalise = Normalisation.normaliserTexte(nom);

    UUID artisteId = resoudreArtisteId(nom, normalise);
    if (artisteRepository.existePourUtilisateur(userId, artisteId)) {
      throw new RessourceDejaExistante("Cet artiste est déjà dans votre liste.");
    }
    if (artisteRepository.compterPourUtilisateur(userId) >= CAP_ARTISTES) {
      throw new ErreurMetier("Limite de 500 artistes atteinte.");
    }

    artisteRepository.ajouter(userId, nom, artisteId);
    executerApresCommit(artisteId, nom, wikidataId);
  }

  /// Diffère la résolution Wikidata/Deezer et le déclenchement du worker jusqu'après le commit
  /// effectif de la transaction.
  ///
  /// <p>Sans cela, l'appel HTTP synchrone vers Wikidata ({@link #resoudreDeezerId}) retenait une
  /// connexion JDBC HikariCP pendant toute sa durée (jusqu'à 5s), et le déclenchement du worker
  /// pouvait s'exécuter avant que l'{@code INSERT} ne soit visible en base (isolation {@code READ
  /// COMMITTED}), faisant manquer l'artiste tout juste ajouté au cycle de fetch qu'il vient
  /// lui-même de déclencher. Exécute immédiatement si aucune transaction n'est active (ex. tests
  /// unitaires sans contexte Spring).
  ///
  /// @param artisteId UUID de l'artiste résolu/créé dans la transaction en cours
  /// @param nom nom affiché tel que saisi
  /// @param wikidataId Q-ID Wikidata optionnel (peut être {@code null})
  private void executerApresCommit(UUID artisteId, String nom, String wikidataId) {
    Runnable action =
        () -> {
          if (wikidataId != null && !wikidataId.isBlank()) {
            resoudreEtPersisterWikidata(artisteId, nom, wikidataId);
          }
          declencherWorkerArtiste();
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private void resoudreEtPersisterWikidata(UUID artisteId, String nom, String wikidataId) {
    try {
      artisteRepository.enregistrerArtisteSource(artisteId, "wikidata", wikidataId);
      String deezerId = resoudreDeezerId(wikidataId);
      if (deezerId != null) {
        artisteRepository.enregistrerArtisteSource(artisteId, "deezer", deezerId);
      }
      artisteRepository.mettreAJourIdsWikidata(artisteId, wikidataId, deezerId);
    } catch (DuplicateKeyException e) {
      log.warn(
          "wikidataId déjà attribué à un autre artiste — conflit ignoré artiste={} wikidataId={}",
          nom,
          wikidataId);
      logAdminService.logAdmin(
          "warn",
          "enrichissement-wikidata",
          "wikidataId déjà attribué à un autre artiste — conflit ignoré",
          Map.of("artiste", nom, "wikidataId", wikidataId));
    }
  }

  /// Résout l'UUID d'un artiste dans le référentiel global, en réutilisant un artiste existant dont
  /// le nom est un doublon flou ({@link CorrespondanceArtiste#estDoublonArtiste}) plutôt que d'en
  /// créer un nouveau. En cas de correspondance exacte de {@code nom_norm}, passe par {@code
  /// upsertArtiste} pour conserver le comportement existant de mise à jour du nom affiché global ;
  /// en cas de correspondance floue seulement, réutilise l'UUID existant sans toucher au nom global
  /// déjà établi (le nom tapé par cet utilisateur reste visible via {@code
  /// user_artistes.artiste_affiche}, indépendant du nom global).
  ///
  /// <p>Public (pas seulement utilisé par {@link #ajouter}) : réutilisé par {@code
  /// PreferencesService.importerArtistes} pour que l'import de préférences applique la même dédup
  /// floue que l'ajout interactif, plutôt qu'une égalité stricte sur {@code nom_norm}.
  ///
  /// @param nom nom affiché tel que saisi
  /// @param normalise nom normalisé ({@link Normalisation#normaliserTexte})
  /// @return UUID de l'artiste existant ou nouvellement créé
  public UUID resoudreArtisteId(String nom, String normalise) {
    Set<String> tokens = CorrespondanceSalle.tokensSignificatifs(normalise);
    for (Map<String, Object> candidat : artisteRepository.chercherCandidatsParTokens(tokens)) {
      String nomNormCandidat = (String) candidat.get("nom_norm");
      if (nomNormCandidat.equals(normalise)) {
        return artisteRepository.upsertArtiste(nom, normalise);
      }
      if (CorrespondanceArtiste.estDoublonArtiste(normalise, nomNormCandidat)) {
        return (UUID) candidat.get("id");
      }
    }
    return artisteRepository.upsertArtiste(nom, normalise);
  }

  /// Indique si une recherche correspond, même partiellement, à un artiste déjà suivi.
  ///
  /// <p>Une égalité stricte laissait passer un faux "Aucun résultat" quand la recherche est un
  /// terme partiel (ex. "Yael" ne matchait pas "yaelnaim") alors que l'artiste a bien été trouvé
  /// puis exclu des suggestions car déjà suivi — l'utilisateur n'avait alors aucun signal
  /// expliquant l'absence de résultat.
  ///
  /// <p>Package-private (plutôt que {@code private}) pour être testable directement.
  ///
  /// @param rechercheNorm nom normalisé de la recherche ({@link Normalisation#normaliserTexte})
  /// @param nomsNorm noms normalisés des artistes déjà suivis
  /// @return {@code true} si un artiste déjà suivi contient la recherche
  static boolean contientCorrespondancePartielle(String rechercheNorm, Set<String> nomsNorm) {
    return nomsNorm.stream().anyMatch(nom -> nom.contains(rechercheNorm));
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// <p>Ne journalise (log applicatif + {@code logs_admin}) que si l'artiste était réellement suivi
  /// — un appel sur un artiste déjà retiré, jamais suivi, ou appartenant à un autre utilisateur ne
  /// doit pas produire une fausse entrée d'audit "artiste supprimé".
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param artisteId UUID stable de l'artiste
  public void supprimer(String userId, UUID artisteId) {
    if (artisteRepository.supprimer(userId, artisteId) == 0) {
      return;
    }
    log.info("artiste supprimé userId={} artisteId={}", userId, artisteId);
    logAdminService.logAdmin(
        "warn",
        "web",
        "artiste supprimé",
        Map.of("userId", userId, "artisteId", artisteId.toString()));
  }

  /// Recherche des artistes par nom via Wikidata Search ({@code wbsearchentities}).
  ///
  /// <p>Ne résout pas le P2722 (Deezer ID) à ce stade — un appel {@code wbgetentities}
  /// supplémentaire par candidat serait disproportionné pour de l'autocomplete interactif ; le
  /// P2722 n'est résolu qu'à la sélection effective (voir {@link #resoudreDeezerId}).
  ///
  /// @param terme terme de recherche saisi par l'utilisateur
  /// @return suggestions Wikidata filtrées par mots-clés de description, vide si aucune ou en cas
  ///   d'échec réseau
  private List<SuggestionArtiste> rechercherWikidata(String terme) {
    try {
      String url =
          WIKIDATA_API_URL
              + "?action=wbsearchentities&search="
              + URLEncoder.encode(terme, StandardCharsets.UTF_8)
              + "&language=fr&format=json&type=item&limit=6";
      ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .header("User-Agent", WIKIDATA_USER_AGENT)
              .exchange(
                  (req, resp) ->
                      new ReponseHttp(resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != 200) {
        log.warn("Wikidata Search erreur HTTP {} pour '{}'", reponse.statut(), terme);
        return List.of();
      }
      return parseWikidataResultats(reponse.corps());
    } catch (Exception e) {
      log.warn("Wikidata Search échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private List<SuggestionArtiste> parseWikidataResultats(String json) {
    List<SuggestionArtiste> resultats = new ArrayList<>();
    try {
      Map<String, Object> root = objectMapper.readValue(json, Map.class);
      List<Map<String, Object>> search = (List<Map<String, Object>>) root.get("search");
      if (search == null) return resultats;

      for (Map<String, Object> item : search) {
        String label = (String) item.get("label");
        String id = (String) item.get("id");
        if (label == null || label.isBlank() || id == null) continue;
        String description = (String) item.get("description");
        // Description absente : on ne peut ni confirmer ni exclure — on garde plutôt que de
        // priver l'utilisateur d'un résultat potentiellement pertinent (simple suggestion,
        // l'utilisateur reste seul décideur au clic).
        if (description != null && !MOTS_CLES_ARTISTE.matcher(description).find()) continue;
        resultats.add(new SuggestionArtiste(label, "wikidata", description, id));
      }
    } catch (Exception _) {
      log.warn("Wikidata Search parsing JSON échoué");
    }
    return resultats;
  }

  /// Résout le P2722 (Deezer ID) d'un artiste Wikidata via {@code wbgetentities}.
  ///
  /// <p>Package-private (plutôt que {@code private}) pour permettre un test par substitution
  /// ({@code Mockito.spy}) évitant un appel réseau réel vers Wikidata en test unitaire.
  ///
  /// @param wikidataId Q-ID Wikidata
  /// @return identifiant Deezer, ou {@code null} si absent ou en cas d'échec réseau
  @SuppressWarnings("unchecked")
  String resoudreDeezerId(String wikidataId) {
    try {
      String url =
          WIKIDATA_API_URL
              + "?action=wbgetentities&ids="
              + URLEncoder.encode(wikidataId, StandardCharsets.UTF_8)
              + "&props=claims&format=json";
      ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .header("User-Agent", WIKIDATA_USER_AGENT)
              .exchange(
                  (req, resp) ->
                      new ReponseHttp(resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != 200) return null;

      Map<String, Object> root = objectMapper.readValue(reponse.corps(), Map.class);
      Map<String, Object> entities = (Map<String, Object>) root.get("entities");
      if (entities == null) return null;
      Map<String, Object> entite = (Map<String, Object>) entities.get(wikidataId);
      if (entite == null) return null;
      Map<String, Object> claims = (Map<String, Object>) entite.get("claims");
      if (claims == null) return null;
      List<Map<String, Object>> p2722 = (List<Map<String, Object>>) claims.get("P2722");
      if (p2722 == null || p2722.isEmpty()) return null;
      Map<String, Object> mainsnak = (Map<String, Object>) p2722.get(0).get("mainsnak");
      Map<String, Object> datavalue = (Map<String, Object>) mainsnak.get("datavalue");
      return (String) datavalue.get("value");
    } catch (Exception e) {
      log.warn("Wikidata wbgetentities échoué pour '{}'", wikidataId, e);
      return null;
    }
  }

  private List<SuggestionArtiste> rechercherDeezer(String terme) {
    try {
      String url =
          DEEZER_URL + "?q=" + URLEncoder.encode(terme, StandardCharsets.UTF_8) + "&limit=6";
      ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .exchange(
                  (req, resp) ->
                      new ReponseHttp(resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != 200) {
        log.warn("Deezer erreur HTTP {} pour '{}'", reponse.statut(), terme);
        return List.of();
      }
      return parseDeezerResultats(reponse.corps());
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

  /// Déclenche le worker en fire-and-forget, sur un thread virtuel — {@link RestClient} est
  /// bloquant (contrairement à l'ancien {@code HttpClient.sendAsync}), le thread virtuel évite de
  /// bloquer l'appelant sans avoir à gérer le cycle de vie d'un executor dédié pour un appel
  /// ponctuel.
  private void declencherWorkerArtiste() {
    Thread.startVirtualThread(
        () -> {
          try {
            restClient
                .post()
                .uri("http://localhost:8081/trigger/artiste")
                .retrieve()
                .toBodilessEntity();
          } catch (Exception e) {
            log.warn("Déclenchement worker artiste impossible", e);
          }
        });
  }

  /// Réponse HTTP brute (statut + corps), utilisée pour reproduire avec {@link RestClient} le
  /// comportement de l'ancien {@code java.net.http.HttpClient} : lire le statut sans lever
  /// d'exception sur les codes non-2xx (contrairement à {@code retrieve()} par défaut).
  private record ReponseHttp(int statut, String corps) {}
}
