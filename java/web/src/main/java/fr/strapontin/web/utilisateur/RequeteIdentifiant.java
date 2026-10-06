/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de la requête PATCH /api/user/identifiant.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteIdentifiant(
    @NotBlank(message = "L'identifiant est obligatoire.") String identifiant) {}
