/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de PATCH /api/user/config/notifications —
 * {@code frequenceNotifications} validé côté service
 * (FREQUENCES_VALIDES) ; {@link NotBlank} évite un NPE
 * sur {@code Set.of(...).contains(null)} si absent du JSON.
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteNotifications(
    @NotBlank(message = "La fréquence de notification est obligatoire.")
        String frequenceNotifications,
    boolean notifArtistes,
    boolean notifSalles,
    boolean notifArtistesPush,
    boolean notifSallesPush,
    boolean notifNouvelleVersion) {}
