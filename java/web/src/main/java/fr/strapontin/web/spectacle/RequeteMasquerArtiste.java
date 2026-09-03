/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import jakarta.validation.constraints.NotBlank;

/// Corps JSON de la requête POST /api/spectacles/masquer-artiste (et demasquer-artiste).
///
/// <p>{@code artisteId} (UUID référentiel) plutôt que {@code artisteNorm} — garantit que tous les
/// concerts du groupe visuellement affiché sont couverts, {@code artiste_norm} pouvant diverger
/// entre concerts d'un même artiste réel selon la source de fetch. Le format UUID lui-même reste
/// vérifié par l'idiome {@code parseUuid} déjà utilisé partout ailleurs, pas par une contrainte
/// {@code @Pattern} dupliquée ici.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteMasquerArtiste(
    @NotBlank(message = "L'identifiant de l'artiste est obligatoire.") String artisteId) {}
