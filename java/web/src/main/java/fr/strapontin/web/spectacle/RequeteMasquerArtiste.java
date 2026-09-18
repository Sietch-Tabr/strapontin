/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de POST /api/spectacles/masquer-artiste (et
 * demasquer-artiste) — {@code artisteId} (UUID référentiel) plutôt que
 * {@code artisteNorm}, qui peut diverger selon la source de fetch. Format
 * UUID vérifié par l'idiome {@code parseUuid}, pas de {@code @Pattern} ici.
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteMasquerArtiste(
    @NotBlank(message = "L'identifiant de l'artiste est obligatoire.") String artisteId) {}
