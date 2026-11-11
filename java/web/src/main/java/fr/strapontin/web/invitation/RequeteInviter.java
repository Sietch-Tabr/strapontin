/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de l'invitation d'une personne par un utilisateur.
 * @param email adresse de la personne invitée
 * @since 3.1.4
 * @author Kwaite
 */
public record RequeteInviter(
    @NotBlank(message = "Format d'email invalide.")
        @Email(message = "Format d'email invalide.")
        @Size(max = 254, message = "Format d'email invalide.")
        String email) {}
