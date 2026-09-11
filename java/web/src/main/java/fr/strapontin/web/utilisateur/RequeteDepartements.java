/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête PATCH /api/user/config/departements.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteDepartements(
    @Size(max = 1000, message = "Le champ départements ne peut pas dépasser 1000 caractères.")
        String departements) {}
