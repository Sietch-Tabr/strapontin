/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import java.util.UUID;

/**
 * Salle candidate à l'enrichissement Wikidata/OSM — {@code latitude}/
 * {@code longitude} portent les coordonnées déjà connues (lieux publiés,
 * Wikidata ou géocodage), pour valider un candidat de découverte contre un
 * GPS déjà établi.
 * @since 2.6.0
 * @author Kwaite
 */
public record SalleAEnrichir(
    UUID id,
    String nom,
    String nomNorm,
    String ville,
    String dept,
    String wikidataId,
    Double latitude,
    Double longitude) {}
