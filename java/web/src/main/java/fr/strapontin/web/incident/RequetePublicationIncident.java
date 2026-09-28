/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de publication d'une communication d'incident : contenu et
 * canaux (email, push, nouveau mot de passe exigé de tous les comptes).
 *
 * @since 3.0.1
 * @author Kwaite
 */
public record RequetePublicationIncident(
    @NotBlank(message = "Titre obligatoire.") @Size(max = 120, message = "Titre trop long.")
        String titre,
    @NotBlank(message = "Message obligatoire.") @Size(max = 2000, message = "Message trop long.")
        String message,
    boolean email,
    boolean push,
    boolean changementMdpForce) {}
