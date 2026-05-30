/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.artiste;

/// Suggestion d'artiste pour la recherche ({@code "base"} ou {@code "deezer"}).
///
/// @since 2.1.0
/// @author Kwaite
public record SuggestionArtiste(String nom, String source) {}
