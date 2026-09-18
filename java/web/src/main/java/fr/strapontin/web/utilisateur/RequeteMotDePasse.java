/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/user/password.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteMotDePasse(
    @NotBlank(message = "Tous les champs sont obligatoires.") String motDePasseActuel,
    @NotBlank(message = "Tous les champs sont obligatoires.")
        @Size(min = 12, message = "Le nouveau mot de passe doit contenir au moins 12 caractères.")
        String motDePasseNouveau) {}
