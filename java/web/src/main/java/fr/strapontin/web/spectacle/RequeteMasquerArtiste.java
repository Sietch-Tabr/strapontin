/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

/// Corps JSON de la requête POST /api/spectacles/masquer-artiste (et demasquer-artiste).
///
/// <p>{@code artisteId} (UUID référentiel, #818) plutôt que {@code artisteNorm} — garantit que
/// tous les concerts du groupe visuellement affiché sont couverts, {@code artiste_norm} pouvant
/// diverger entre concerts d'un même artiste réel selon la source de fetch.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteMasquerArtiste(String artisteId) {}
