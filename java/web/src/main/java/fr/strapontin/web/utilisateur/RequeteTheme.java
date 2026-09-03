/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;

/// Corps JSON de la requête PATCH /api/user/config/theme.
///
/// <p>{@code valeur} n'est volontairement pas contraint sur l'ensemble des thèmes acceptés ({@link
/// jakarta.validation.constraints.Pattern}) — déjà défini une seule fois côté service ({@code
/// UserConfigService.THEMES_VALIDES}), dupliquer la liste créerait un risque de dérive. {@link
/// NotBlank} évite qu'une valeur {@code null} atteigne {@code Set.of(...).contains(null)}, qui lève
/// une {@link NullPointerException} sur un ensemble immuable.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteTheme(@NotBlank(message = "Le thème est obligatoire.") String valeur) {}
