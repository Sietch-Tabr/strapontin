/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.UUID;

/**
 * Salle candidate à l'enrichissement Wikidata/OSM.
 * @since 2.6.0
 * @author Kwaite
 */
public record SalleAEnrichir(
    UUID id, String nom, String nomNorm, String ville, String dept, String wikidataId) {}
