/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;

/// Corps JSON de la requête PATCH /api/user/config/notifications.
///
/// <p>{@code frequenceNotifications} n'est volontairement pas contraint par {@link
/// jakarta.validation.constraints.Pattern} sur l'ensemble des valeurs acceptées (déjà défini une
/// seule fois côté service, {@code UserConfigService.FREQUENCES_VALIDES}) — dupliquer la liste
/// créerait un risque de dérive entre les deux. {@link NotBlank} évite en revanche qu'une valeur
/// {@code null} (champ absent du JSON) atteigne {@code Set.of(...).contains(null)}, qui lève une
/// {@link NullPointerException} sur un ensemble immuable.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteNotifications(
    @NotBlank(message = "La fréquence de notification est obligatoire.")
        String frequenceNotifications,
    boolean notifArtistes,
    boolean notifSalles,
    boolean notifArtistesPush,
    boolean notifSallesPush) {}
