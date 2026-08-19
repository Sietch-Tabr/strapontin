/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête POST /api/push (abonnement push).
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteAbonnement(
    @NotBlank(message = "Abonnement incomplet.")
        @Size(max = 2048, message = "Données d'abonnement invalides.")
        String endpoint,
    @NotNull(message = "Abonnement incomplet.") @Valid ClesAbonnement keys) {}
