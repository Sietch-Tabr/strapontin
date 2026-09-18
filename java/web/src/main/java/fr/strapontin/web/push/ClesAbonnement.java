/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Clés cryptographiques d'un abonnement Web Push.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ClesAbonnement(
    @NotBlank(message = "Abonnement incomplet.")
        @Size(max = 256, message = "Données d'abonnement invalides.")
        String p256dh,
    @NotBlank(message = "Abonnement incomplet.")
        @Size(max = 256, message = "Données d'abonnement invalides.")
        String auth) {}
