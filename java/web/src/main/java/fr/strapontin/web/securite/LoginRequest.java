/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de la requête POST /api/auth/login.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record LoginRequest(
    @NotBlank(message = "L'identifiant est requis.") String identifiant,
    @NotBlank(message = "Le mot de passe est requis.") String motDePasse) {}
