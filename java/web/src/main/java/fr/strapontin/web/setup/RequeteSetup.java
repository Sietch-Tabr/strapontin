/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête POST /api/setup.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteSetup(
    @NotBlank(message = "L'identifiant est requis.")
        @Size(min = 3, message = "L'identifiant doit contenir au moins 3 caractères.")
        String identifiant,
    @NotBlank(message = "L'adresse email est invalide.")
        @Email(message = "L'adresse email est invalide.")
        String email,
    @NotBlank(message = "Le mot de passe est requis.")
        @Size(min = 12, message = "Le mot de passe doit contenir au moins 12 caractères.")
        String motDePasse,
    String baseUrl,
    String contactEmail) {}
