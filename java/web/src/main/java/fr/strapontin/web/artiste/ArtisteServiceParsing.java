/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Analyse des réponses JSON brutes Wikidata/Deezer Search en suggestions
 * d'artiste — extrait d'{@link ArtisteService} (limite 500 lignes), pure
 * analyse sans état ni appel réseau (les méthodes d'appel restent sur
 * {@link ArtisteService} pour leur substitution {@code Mockito.spy} en test).
 * @since 2.9.1
 * @author Kwaite
 */
final class ArtisteServiceParsing {

  private static final Logger LOG = LoggerFactory.getLogger(ArtisteServiceParsing.class);

  // Mots-clés (description Wikidata, fr/en) distinguant un artiste musical ou
  // un humoriste des homonymes non pertinents pour l'autocomplete.
  private static final Pattern MOTS_CLES_ARTISTE =
      Pattern.compile(
          "chanteur|chanteuse|musicien|musicienne|rappeur|rappeuse|compositeur|compositrice"
              + "|groupe|humoriste|dj\\b"
              + "|singer|rapper|musician|band|composer|songwriter|comedian|humorist",
          Pattern.CASE_INSENSITIVE);

  private ArtisteServiceParsing() {}

  @SuppressWarnings("unchecked")
  /* package-private : appelé par ArtisteService (même package). */
  static List<SuggestionArtiste> parseWikidataResultats(
      final ObjectMapper objectMapper, final String json) {
    final List<SuggestionArtiste> resultats = new ArrayList<>();
    try {
      final Map<String, Object> root = objectMapper.readValue(json, Map.class);
      final List<Map<String, Object>> search = (List<Map<String, Object>>) root.get("search");
      if (search == null) {
        return resultats;
      }

      for (final Map<String, Object> item : search) {
        final String label = (String) item.get("label");
        final String id = (String) item.get("id");
        if (label == null || label.isBlank() || id == null) {
          continue;
        }
        final String description = (String) item.get("description");
        // Description absente : on ne peut ni confirmer ni exclure — on garde
        // plutôt que de priver l'utilisateur d'un résultat potentiellement
        // pertinent (simple suggestion, l'utilisateur reste seul décideur au
        // clic).
        if (description != null && !MOTS_CLES_ARTISTE.matcher(description).find()) {
          continue;
        }
        resultats.add(new SuggestionArtiste(label, "wikidata", description, id));
      }
    } catch (Exception _) {
      LOG.warn("Wikidata Search parsing JSON échoué");
    }
    return resultats;
  }

  // Deezer renvoie parfois deux fiches de même nom : la plus populaire, vue en
  // premier, est gardée.
  private static Predicate<Map<String, Object>> distinctParNom() {
    final Set<String> vus = new HashSet<>();
    return a -> vus.add((String) a.get("name"));
  }

  @SuppressWarnings("unchecked")
  /* package-private : appelé par ArtisteService (même package). */
  static List<SuggestionArtiste> parseDeezerResultats(
      final ObjectMapper objectMapper, final String json) {
    final List<SuggestionArtiste> resultats = new ArrayList<>();
    try {
      final Map<String, Object> root = objectMapper.readValue(json, Map.class);
      final List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
      if (data == null) {
        return resultats;
      }

      data.stream()
          .sorted(
              (a, b) ->
                  Integer.compare(
                      ((Number) b.getOrDefault("nb_fan", 0)).intValue(),
                      ((Number) a.getOrDefault("nb_fan", 0)).intValue()))
          .filter(a -> a.get("name") instanceof String nom && !nom.isBlank())
          .filter(distinctParNom())
          .forEach(
              a ->
                  resultats.add(
                      new SuggestionArtiste(
                          (String) a.get("name"),
                          "deezer",
                          null,
                          null,
                          ((Number) a.getOrDefault("nb_fan", 0)).intValue())));
    } catch (Exception _) {
      LOG.warn("Deezer parsing JSON échoué");
    }
    return resultats;
  }

  /* package-private : appelé par ArtisteService (même package). */
  static String compacter(final String nom) {
    return Normalisation.normaliserTexte(nom).replace(" ", "");
  }

  /* package-private : appelé par ArtisteService (même package). */
  static void ajouterSuggestionsInedites(
      final List<SuggestionArtiste> cible,
      final Set<String> nomsDejaPresents,
      final List<SuggestionArtiste> source) {
    for (final SuggestionArtiste r : source) {
      final String norm = compacter(r.nom());
      if (!nomsDejaPresents.contains(norm)) {
        cible.add(r);
        nomsDejaPresents.add(norm);
      }
    }
  }
}
