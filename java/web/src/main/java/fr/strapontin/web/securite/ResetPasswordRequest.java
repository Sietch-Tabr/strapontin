/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/auth/reset-password.
 *
 * @param identifiant optionnel — n'est validé (format/longueur) que lorsqu'il
 *     est fourni, voir {@link AuthController#reinitialiserMotDePasse}
 * @since 2.1.0
 * @author Kwaite
 */
public record ResetPasswordRequest(
    @NotBlank(message = "Token et mot de passe requis.") String token,
    @NotBlank(message = "Token et mot de passe requis.")
        @Size(min = 12, message = "Le mot de passe doit contenir au moins 12 caractères.")
        String motDePasse,
    String identifiant) {}
