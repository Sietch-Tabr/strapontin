/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.artiste;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/// Corps JSON de la requête POST /api/artistes.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteAjouter(
    @NotBlank(message = "Le nom de l'artiste est obligatoire.")
        @Size(max = 200, message = "Nom trop long (200 caractères max).")
        String nom) {}
