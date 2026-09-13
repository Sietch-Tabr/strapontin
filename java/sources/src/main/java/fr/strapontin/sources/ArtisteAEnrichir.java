/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.UUID;

/**
 * Artiste candidat à l'enrichissement Wikidata/Deezer.
 * @since 2.6.0
 * @author Kwaite
 */
public record ArtisteAEnrichir(
    UUID id, String nom, String nomNorm, String wikidataId, String deezerId) {}
