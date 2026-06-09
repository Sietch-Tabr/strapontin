/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête PATCH /api/user/profile.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteProfil(
    @Email(message = "Format d'email invalide.") @Size(max = 254) String email) {}
