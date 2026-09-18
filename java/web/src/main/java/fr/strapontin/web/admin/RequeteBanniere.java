/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/admin/banniere.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteBanniere(
    @NotBlank(message = "Le message est requis.")
        @Size(max = 500, message = "Message trop long (500 caractères max).")
        String message,
    String type) {}
