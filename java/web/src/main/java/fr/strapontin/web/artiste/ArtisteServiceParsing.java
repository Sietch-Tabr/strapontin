/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyse des réponses JSON brutes Wikidata Search et Deezer Search en suggestions d'artiste.
 *
 * <p>Extrait d'{@link ArtisteService} pour rester sous la limite de 500 lignes par fichier — pure
 * analyse sans état, sans appel réseau (contrairement à {@link ArtisteService#rechercherWikidata}/
 * {@link ArtisteService#rechercherDeezer}, qui restent sur {@link ArtisteService} pour préserver
 * leur substitution par {@code Mockito.spy} en test).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class ArtisteServiceParsing {

  private ArtisteServiceParsing() {}

  private static final Logger LOG = LoggerFactory.getLogger(ArtisteServiceParsing.class);

  // Mots-clés (description Wikidata, fr/en) distinguant un artiste musical ou un humoriste
  // des homonymes non pertinents pour l'autocomplete.
  private static final Pattern MOTS_CLES_ARTISTE =
      Pattern.compile(
          "chanteur|chanteuse|musicien|musicienne|rappeur|rappeuse|compositeur|compositrice"
              + "|groupe|humoriste|dj\\b"
              + "|singer|rapper|musician|band|composer|songwriter|comedian|humorist",
          Pattern.CASE_INSENSITIVE);

  @SuppressWarnings("unchecked")
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
        // Description absente : on ne peut ni confirmer ni exclure — on garde plutôt que de
        // priver l'utilisateur d'un résultat potentiellement pertinent (simple suggestion,
        // l'utilisateur reste seul décideur au clic).
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

  @SuppressWarnings("unchecked")
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
          .map(a -> (String) a.get("name"))
          .filter(name -> name != null && !name.isBlank())
          .distinct()
          .forEach(name -> resultats.add(new SuggestionArtiste(name, "deezer")));
    } catch (Exception _) {
      LOG.warn("Deezer parsing JSON échoué");
    }
    return resultats;
  }
}
