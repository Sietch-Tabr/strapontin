/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.UUID;

/// Artiste candidat à l'enrichissement Wikidata/Deezer.
///
/// @param id         UUID interne stable de l'artiste
/// @param nom        nom brut affiché
/// @param nomNorm    nom normalisé ({@link fr.strapontin.core.Normalisation#normaliserTexte})
/// @param wikidataId Q-ID Wikidata déjà connu, ou null si à découvrir
/// @param deezerId   identifiant Deezer déjà connu, ou null si à découvrir
/// @since 2.6.0
/// @author Kwaite
public record ArtisteAEnrichir(
    UUID id, String nom, String nomNorm, String wikidataId, String deezerId) {}
