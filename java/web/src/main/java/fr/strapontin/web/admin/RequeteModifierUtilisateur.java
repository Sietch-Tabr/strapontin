/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête PATCH /api/admin/utilisateurs/{userId}.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteModifierUtilisateur(
    @Size(max = 100, message = "Nom trop long (100 caractères max).") String nom,
    @Email(message = "Format d'email invalide.") String email,
    String role) {}
