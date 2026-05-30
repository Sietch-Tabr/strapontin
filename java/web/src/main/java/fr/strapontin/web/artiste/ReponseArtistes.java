/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import java.util.List;

/// Réponse de l'endpoint GET /api/artistes.
///
/// @since 2.1.0
/// @author Kwaite
public record ReponseArtistes(
    List<ArtisteUtilisateur> artistes, List<SuggestionArtiste> suggestions, boolean dejaLeSuivi) {}
