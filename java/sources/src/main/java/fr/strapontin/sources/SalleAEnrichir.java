/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.UUID;

/// Salle candidate à l'enrichissement Wikidata/OSM (#711).
///
/// @param id         UUID interne stable de la salle
/// @param nom        nom brut affiché
/// @param nomNorm    nom normalisé ({@link fr.strapontin.core.Normalisation#normaliserTexte})
/// @param ville      ville connue, peut être null
/// @param dept       département connu, peut être null
/// @param wikidataId Q-ID Wikidata déjà connu, ou null si à découvrir
/// @since 2.6.0
/// @author Kwaite
public record SalleAEnrichir(
    UUID id, String nom, String nomNorm, String ville, String dept, String wikidataId) {}
