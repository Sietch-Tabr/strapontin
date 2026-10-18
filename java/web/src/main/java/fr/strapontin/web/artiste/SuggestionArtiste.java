/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

/**
 * Suggestion d'artiste pour la recherche ({@code "base"}, {@code "deezer"}
 * ou {@code "wikidata"}) — {@code description}/{@code wikidataId} restent
 * {@code null} pour les sources autres que Wikidata, {@code fans} (popularité
 * Deezer) pour les autres que Deezer : de quoi distinguer deux résultats voisins.
 * @since 2.1.0
 * @author Kwaite
 */
public record SuggestionArtiste(
    String nom, String source, String description, String wikidataId, Integer fans) {

  /**
   * Construit une suggestion sans popularité (sources {@code "base"} et {@code "wikidata"}).
   *
   * @param nom nom affiché
   * @param source origine de la suggestion
   * @param description description Wikidata, peut être {@code null}
   * @param wikidataId Q-ID Wikidata, peut être {@code null}
   */
  public SuggestionArtiste(
      final String nom, final String source, final String description, final String wikidataId) {
    this(nom, source, description, wikidataId, null);
  }

  /**
   * Construit une suggestion sans métadonnées Wikidata (sources {@code "base"}
   * /{@code "deezer"}).
   *
   * @param nom nom affiché
   * @param source origine de la suggestion
   */
  public SuggestionArtiste(final String nom, final String source) {
    this(nom, source, null, null);
  }
}
