/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de la requête POST /api/admin/utilisateurs.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteCreerUtilisateur(
    @NotBlank(message = "Format d'email invalide.") @Email(message = "Format d'email invalide.")
        String email) {}
