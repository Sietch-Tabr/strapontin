/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête POST /api/salles/signaler.
 *
 * @since 2.2.5
 * @author Kwaite
 */
public record RequeteSignalementSalle(
    @NotBlank(message = "Le nom de la salle est requis.")
        @Size(max = 200, message = "Le nom ne peut pas dépasser 200 caractères.")
        String nom,
    @Size(max = 100, message = "La ville ne peut pas dépasser 100 caractères.") String ville,
    @Size(max = 500, message = "L'URL ne peut pas dépasser 500 caractères.") String urlSite,
    @Size(max = 1000, message = "Le commentaire ne peut pas dépasser 1000 caractères.")
        String commentaire) {}
