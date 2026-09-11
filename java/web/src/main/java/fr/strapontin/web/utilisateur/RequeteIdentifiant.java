/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête PATCH /api/user/identifiant.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteIdentifiant(
    @NotBlank(message = "L'identifiant est obligatoire.")
        @Size(min = 3, max = 30, message = "L'identifiant doit contenir entre 3 et 30 caractères.")
        @Pattern(
            regexp = "[a-zA-Z0-9._-]+",
            message =
                "L'identifiant ne peut contenir que des lettres, chiffres, points,"
                    + " tirets et underscores.")
        String identifiant) {}
