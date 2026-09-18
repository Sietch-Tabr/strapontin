/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/parametres/email-change.
 *
 * @since 2.2.0
 * @author Kwaite
 */
public record RequeteEmailChange(
    @NotBlank(message = "L'adresse email est requise.")
        @Email(message = "Format d'email invalide.")
        @Size(max = 254, message = "Format d'email invalide.")
        String email) {}
