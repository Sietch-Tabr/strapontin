/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

/// Suggestion d'artiste pour la recherche ({@code "base"}, {@code "deezer"} ou {@code
/// "wikidata"}).
///
/// @param nom         nom affiché
/// @param source      origine de la suggestion
/// @param description description Wikidata ({@code null} pour les autres sources)
/// @param wikidataId Q-ID Wikidata ({@code null} pour les autres sources)
/// @since 2.1.0
/// @author Kwaite
public record SuggestionArtiste(String nom, String source, String description, String wikidataId) {

  /// Construit une suggestion sans métadonnées Wikidata (sources {@code "base"}/{@code
  /// "deezer"}).
  ///
  /// @param nom    nom affiché
  /// @param source origine de la suggestion
  public SuggestionArtiste(String nom, String source) {
    this(nom, source, null, null);
  }
}
