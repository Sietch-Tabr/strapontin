/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête POST /api/user/first-login.
///
/// @since 2.1.0
/// @author Kwaite
public record RequetePremierLogin(
    @NotBlank(message = "Tous les champs sont obligatoires.")
        @Size(max = 100, message = "Nom invalide (1-100 caractères).")
        String nom,
    @NotBlank(message = "Tous les champs sont obligatoires.")
        @Size(min = 3, max = 30, message = "L'identifiant doit contenir entre 3 et 30 caractères.")
        String identifiant,
    @NotBlank(message = "Tous les champs sont obligatoires.") String motDePasseActuel,
    @NotBlank(message = "Tous les champs sont obligatoires.")
        @Size(min = 12, message = "Le mot de passe doit contenir au moins 12 caractères.")
        String motDePasseNouveau) {}
