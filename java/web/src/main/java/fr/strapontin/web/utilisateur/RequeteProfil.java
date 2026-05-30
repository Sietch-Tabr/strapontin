/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête PATCH /api/user/profile.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteProfil(
    @Size(min = 1, max = 100, message = "Nom invalide (1-100 caractères).") String nom,
    @Email(message = "Format d'email invalide.") @Size(max = 254) String email) {}
