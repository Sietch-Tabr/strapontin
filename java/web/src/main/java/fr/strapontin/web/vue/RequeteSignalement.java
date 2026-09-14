/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la requête de signalement depuis la page d'aide — {@code type}
 * (bug/suggestion/question) inconnu retombe silencieusement sur {@code
 * question}, voir {@link AideRestController#signaler}.
 * @since 2.2.0
 * @author Kwaite
 */
public record RequeteSignalement(
    String type,
    @NotBlank(message = "Le message est requis.")
        @Size(max = 2000, message = "Le message ne peut pas dépasser 2000 caractères.")
        String message) {}
