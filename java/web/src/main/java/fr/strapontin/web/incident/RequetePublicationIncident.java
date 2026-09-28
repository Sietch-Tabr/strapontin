/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps JSON de publication d'une communication d'incident.
 *
 * @param titre titre affiché
 * @param message message affiché (texte brut)
 * @param email {@code true} pour prévenir chaque compte par email
 * @param push {@code true} pour notifier tous les abonnements push
 * @param changementMdpForce {@code true} pour exiger un nouveau mot de passe de
 *     tous les comptes à leur prochaine connexion
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
