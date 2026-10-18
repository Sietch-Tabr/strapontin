/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la requête de renouvellement des clés VAPID.
 * @param motif raison du renouvellement, journalisée et donnée aux
 *     utilisateurs prévenus
 * @since 3.0.6
 * @author Kwaite
 */
public record RequeteVapid(
    @NotBlank(message = "Le motif du renouvellement est obligatoire.")
        @Size(max = 300, message = "Le motif ne peut dépasser 300 caractères.")
        String motif) {}
