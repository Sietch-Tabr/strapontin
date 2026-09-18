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
import fr.strapontin.web.WorkerTriggerUtils;
import fr.strapontin.web.admin.LogAdminService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * Service de gestion des artistes suivis (recherche en base + API Deezer,
 * vérification du cap, déclenchement du worker).
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class ArtisteService {

  private static final Logger LOG = LoggerFactory.getLogger(ArtisteService.class);
  private static final String DEEZER_URL = "https://api.deezer.com/search/artist";
  private static final String WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php";
  private static final String WIKIDATA_USER_AGENT =
      "Strapontin/2.6.3 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr)";
  private static final int CAP_ARTISTES = 500;
  private static final int LONGUEUR_MIN_RECHERCHE_EXTERNE = 2;
  private static final int NB_MIN_SUGGESTIONS_BASE_AVANT_EXTERNE = 3;

  private final ArtisteRepository artisteRepository;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final LogAdminService logAdminService;

  /**
   * @param artisteRepository accès aux données artistes
   * @param objectMapper désérialiseur JSON (pour l'API Deezer)
   * @param restClient client HTTP partagé (timeout 5s/10s défini dans {@link
   *     fr.strapontin.web.WebConfiguration})
   * @param logAdminService journalisation des actions admin
   */
  public ArtisteService(
      final ArtisteRepository artisteRepository,
      final ObjectMapper objectMapper,
      final RestClient restClient,
      final LogAdminService logAdminService) {
    this.artisteRepository = artisteRepository;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste les artistes suivis, avec suggestions si {@code q} est fourni :
   * complète la base avec Wikidata Search puis Deezer si toujours moins de
   * 3 résultats (repli historique, utile aux artistes sans page Wikidata).
   * @param userId identifiant de l'utilisateur
   * @param terme terme de recherche (optionnel, min 2 caractères)
   * @return la liste des artistes et les suggestions
   */
  public ReponseArtistes lister(final String userId, final String terme) {
    final String recherche = terme.strip().substring(0, Math.min(terme.strip().length(), 100));

    final List<ArtisteUtilisateur> artistes = artisteRepository.listerPourUtilisateur(userId);

    List<SuggestionArtiste> suggestions = List.of();
    boolean dejaLeSuivi = false;

    if (recherche.length() >= LONGUEUR_MIN_RECHERCHE_EXTERNE) {
      final String rechercheNorm = Normalisation.normaliserTexte(recherche);
      final Set<String> nomsSuivisNorm =
          artistes.stream()
              .map(a -> Normalisation.normaliserTexte(a.artisteAffiche()))
              .collect(Collectors.toSet());

      final List<String> enBase = artisteRepository.rechercherEnBase("%" + rechercheNorm + "%");

      final List<SuggestionArtiste> resultatsSuggestions =
          enBase.stream()
              .filter(a -> !nomsSuivisNorm.contains(Normalisation.normaliserTexte(a)))
              .map(a -> new SuggestionArtiste(a, "base"))
              .collect(Collectors.toCollection(ArrayList::new));

      final Set<String> nomsDejaPresents =
          resultatsSuggestions.stream()
              .map(s -> Normalisation.normaliserTexte(s.nom()))
              .collect(Collectors.toCollection(HashSet::new));
      nomsDejaPresents.addAll(nomsSuivisNorm);

      // Wikidata et Deezer sont interrogés en parallèle (chacun jusqu'à 10 s de
      // timeout côté RestClient partagé) plutôt que séquentiellement : un
      // enchaînement séquentiel peut approcher les 20 s dans le pire cas (les
      // deux appels au plafond de leur timeout), constaté en E2E (webkit,
      // timeout d'assertion à 25 s dépassé). En parallèle, l'attente totale est
      // bornée par le plus lent des deux, pas leur somme.
      if (resultatsSuggestions.size() < NB_MIN_SUGGESTIONS_BASE_AVANT_EXTERNE) {
        final CompletableFuture<List<SuggestionArtiste>> futureWikidata =
            CompletableFuture.supplyAsync(() -> rechercherWikidata(recherche));
        final CompletableFuture<List<SuggestionArtiste>> futureDeezer =
            CompletableFuture.supplyAsync(() -> rechercherDeezer(recherche));
        ajouterSuggestionsInedites(resultatsSuggestions, nomsDejaPresents, futureWikidata.join());
        ajouterSuggestionsInedites(resultatsSuggestions, nomsDejaPresents, futureDeezer.join());
      }

      suggestions = resultatsSuggestions;
      dejaLeSuivi = contientCorrespondancePartielle(rechercheNorm, nomsSuivisNorm);
    }

    return new ReponseArtistes(artistes, suggestions, dejaLeSuivi);
  }

  private static void ajouterSuggestionsInedites(
      final List<SuggestionArtiste> cible,
      final Set<String> nomsDejaPresents,
      final List<SuggestionArtiste> source) {
    for (final SuggestionArtiste r : source) {
      final String norm = Normalisation.normaliserTexte(r.nom());
      if (!nomsDejaPresents.contains(norm)) {
        cible.add(r);
        nomsDejaPresents.add(norm);
      }
    }
  }

  /**
   * Ajoute un artiste (délégation vers ajouter(String, String, String)).
   * @param userId identifiant de l'utilisateur
   * @param nom nom de l'artiste (non-vide, max 200 car.)
   * @throws RessourceDejaExistante si l'artiste est déjà suivi
   * @throws ErreurMetier si tribute/hommage ou cap de 500 artistes atteint
   */
  @Transactional
  public void ajouter(final String userId, final String nom) {
    ajouter(userId, nom, null);
  }

  /**
   * Ajoute un artiste (doublon/cap/résolution). wikidataId : IDs persistés.
   * @param userId identifiant de l'utilisateur
   * @param nom nom de l'artiste (non-vide, max 200 car.)
   * @param wikidataId Q-ID Wikidata optionnel (peut être {@code null})
   * @throws RessourceDejaExistante si l'artiste est déjà suivi
   * @throws ErreurMetier si tribute/hommage ou cap de 500 artistes atteint
   */
  @Transactional
  public void ajouter(final String userId, final String nom, final String wikidataId) {
    if (FiltreArtiste.estTribute(nom)) {
      throw new ErreurMetier(
          "Ce nom ressemble à un hommage/tribute — suivez l'artiste original plutôt que"
              + " l'attraction dédiée.");
    }
    final String normalise = Normalisation.normaliserTexte(nom);

    final UUID artisteId = resoudreArtisteId(nom, normalise);
    if (artisteRepository.existePourUtilisateur(userId, artisteId)) {
      throw new RessourceDejaExistante("Cet artiste est déjà dans votre liste.");
    }
    if (artisteRepository.compterPourUtilisateur(userId) >= CAP_ARTISTES) {
      throw new ErreurMetier("Limite de 500 artistes atteinte.");
    }

    artisteRepository.ajouter(userId, nom, artisteId);
    executerApresCommit(artisteId, nom, wikidataId);
  }

  /**
   * Diffère la résolution Wikidata/Deezer et le worker après le commit
   * (évite de retenir une connexion JDBC, et un fetch manquant l'artiste —
   * READ COMMITTED). Exécute immédiatement hors transaction (tests).
   * @param artisteId UUID de l'artiste résolu/créé dans la transaction
   * @param nom nom affiché tel que saisi
   * @param wikidataId Q-ID Wikidata optionnel (peut être {@code null})
   */
  private void executerApresCommit(
      final UUID artisteId, final String nom, final String wikidataId) {
    final Runnable action =
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

  private void resoudreEtPersisterWikidata(
      final UUID artisteId, final String nom, final String wikidataId) {
    try {
      artisteRepository.enregistrerArtisteSource(artisteId, "wikidata", wikidataId);
      final String deezerId = resoudreDeezerId(wikidataId);
      if (deezerId != null) {
        artisteRepository.enregistrerArtisteSource(artisteId, "deezer", deezerId);
      }
      artisteRepository.mettreAJourIdsWikidata(artisteId, wikidataId, deezerId);
    } catch (DuplicateKeyException e) {
      LOG.warn(
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

  /**
   * Résout l'UUID d'un artiste, réutilisant un doublon flou ({@link
   * CorrespondanceArtiste#estDoublonArtiste}). Public : aussi utilisé par
   * PreferencesService.importerArtistes.
   * @param nom nom affiché tel que saisi
   * @param normalise nom normalisé ({@link Normalisation#normaliserTexte})
   * @return UUID de l'artiste existant ou nouvellement créé
   */
  public UUID resoudreArtisteId(final String nom, final String normalise) {
    final Set<String> tokens = CorrespondanceSalle.tokensSignificatifs(normalise);
    for (final Map<String, Object> candidat :
        artisteRepository.chercherCandidatsParTokens(tokens)) {
      final String nomNormCandidat = (String) candidat.get("nom_norm");
      if (nomNormCandidat.equals(normalise)) {
        return artisteRepository.upsertArtiste(nom, normalise);
      }
      if (CorrespondanceArtiste.estDoublonArtiste(normalise, nomNormCandidat)) {
        return (UUID) candidat.get("id");
      }
    }
    return artisteRepository.upsertArtiste(nom, normalise);
  }

  /**
   * Indique si une recherche correspond, même partiellement, à un artiste
   * déjà suivi (évite un faux "Aucun résultat" sur terme partiel, ex.
   * "Yael" ne matchait pas "yaelnaim"). Package-private pour être testable.
   * @param rechercheNorm nom normalisé de la recherche
   * @param nomsNorm noms normalisés des artistes déjà suivis
   * @return {@code true} si un artiste déjà suivi contient la recherche
   */
  /* package-private : visibilité élargie pour les tests (même package). */
  static boolean contientCorrespondancePartielle(
      final String rechercheNorm, final Set<String> nomsNorm) {
    return nomsNorm.stream().anyMatch(nom -> nom.contains(rechercheNorm));
  }

  /**
   * Supprime un artiste de la liste de suivi (ne journalise que s'il était
   * réellement suivi — évite une fausse entrée d'audit sur un artiste déjà
   * retiré, jamais suivi, ou d'un autre utilisateur).
   * @param userId identifiant de l'utilisateur
   * @param artisteId UUID stable de l'artiste
   */
  public void supprimer(final String userId, final UUID artisteId) {
    if (artisteRepository.supprimer(userId, artisteId) == 0) {
      return;
    }
    LOG.info("artiste supprimé userId={} artisteId={}", userId, artisteId);
    logAdminService.logAdmin(
        "warn",
        "web",
        "artiste supprimé",
        Map.of("userId", userId, "artisteId", artisteId.toString()));
  }

  /**
   * Recherche des artistes par nom via Wikidata Search (wbsearchentities).
   * P2722 (Deezer ID) non résolu ici (coût disproportionné en autocomplete),
   * voir {@link #resoudreDeezerId}. Package-private pour test par mock.
   * @param terme terme de recherche saisi par l'utilisateur
   * @return suggestions Wikidata, vide si aucune ou en cas d'échec réseau
   */
  /* package-private : substituable par mock partiel en test (même package). */
  List<SuggestionArtiste> rechercherWikidata(final String terme) {
    try {
      final String url =
          WIKIDATA_API_URL
              + "?action=wbsearchentities&search="
              + URLEncoder.encode(terme, StandardCharsets.UTF_8)
              + "&language=fr&format=json&type=item&limit=6";
      final WorkerTriggerUtils.ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .header("User-Agent", WIKIDATA_USER_AGENT)
              .exchange(
                  (_, resp) ->
                      new WorkerTriggerUtils.ReponseHttp(
                          resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != HttpStatus.OK.value()) {
        LOG.warn("Wikidata Search erreur HTTP {} pour '{}'", reponse.statut(), terme);
        return List.of();
      }
      return parseWikidataResultats(reponse.corps());
    } catch (Exception e) {
      LOG.warn("Wikidata Search échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  private List<SuggestionArtiste> parseWikidataResultats(final String json) {
    return ArtisteServiceParsing.parseWikidataResultats(objectMapper, json);
  }

  /**
   * Résout le P2722 (Deezer ID) via wbgetentities. Package-private pour
   * test par mock (évite un appel réseau réel en test unitaire).
   * @param wikidataId Q-ID Wikidata
   * @return identifiant Deezer, ou {@code null} si absent ou échec réseau
   */
  @SuppressWarnings("unchecked")
  /* package-private : substituable par mock partiel en test (même package). */
  String resoudreDeezerId(final String wikidataId) {
    try {
      final String url =
          WIKIDATA_API_URL
              + "?action=wbgetentities&ids="
              + URLEncoder.encode(wikidataId, StandardCharsets.UTF_8)
              + "&props=claims&format=json";
      final WorkerTriggerUtils.ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .header("User-Agent", WIKIDATA_USER_AGENT)
              .exchange(
                  (_, resp) ->
                      new WorkerTriggerUtils.ReponseHttp(
                          resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != HttpStatus.OK.value()) {
        return null;
      }

      final Map<String, Object> root = objectMapper.readValue(reponse.corps(), Map.class);
      final Map<String, Object> entities = (Map<String, Object>) root.get("entities");
      if (entities == null) {
        return null;
      }
      final Map<String, Object> entite = (Map<String, Object>) entities.get(wikidataId);
      if (entite == null) {
        return null;
      }
      final Map<String, Object> claims = (Map<String, Object>) entite.get("claims");
      if (claims == null) {
        return null;
      }
      final List<Map<String, Object>> p2722 = (List<Map<String, Object>>) claims.get("P2722");
      if (p2722 == null || p2722.isEmpty()) {
        return null;
      }
      final Map<String, Object> mainsnak = (Map<String, Object>) p2722.get(0).get("mainsnak");
      final Map<String, Object> datavalue = (Map<String, Object>) mainsnak.get("datavalue");
      return (String) datavalue.get("value");
    } catch (Exception e) {
      LOG.warn("Wikidata wbgetentities échoué pour '{}'", wikidataId, e);
      return null;
    }
  }

  /**
   * @param terme terme de recherche saisi par l'utilisateur
   * @return suggestions Deezer, vide si aucune ou en cas d'échec réseau
   *     <p>Package-private pour permettre un test par substitution ({@code
   *     Mockito.spy}), même motif que {@link #rechercherWikidata} .
   */
  /* package-private : substituable par mock partiel en test (même package). */
  List<SuggestionArtiste> rechercherDeezer(final String terme) {
    try {
      final String url =
          DEEZER_URL + "?q=" + URLEncoder.encode(terme, StandardCharsets.UTF_8) + "&limit=6";
      final WorkerTriggerUtils.ReponseHttp reponse =
          restClient
              .get()
              .uri(url)
              .exchange(
                  (_, resp) ->
                      new WorkerTriggerUtils.ReponseHttp(
                          resp.getStatusCode().value(), resp.bodyTo(String.class)));
      if (reponse.statut() != HttpStatus.OK.value()) {
        LOG.warn("Deezer erreur HTTP {} pour '{}'", reponse.statut(), terme);
        return List.of();
      }
      return parseDeezerResultats(reponse.corps());
    } catch (Exception e) {
      LOG.warn("Deezer recherche artiste échouée pour '{}'", terme, e);
      return List.of();
    }
  }

  private List<SuggestionArtiste> parseDeezerResultats(final String json) {
    return ArtisteServiceParsing.parseDeezerResultats(objectMapper, json);
  }

  private void declencherWorkerArtiste() {
    WorkerTriggerUtils.declencherWorkerAsync(
        restClient,
        LOG,
        "http://localhost:8081/trigger/artiste",
        "Déclenchement worker artiste impossible");
  }
}
