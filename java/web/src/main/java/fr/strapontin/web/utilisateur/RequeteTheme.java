/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de PATCH /api/user/config/theme — {@code valeur}
 * validée côté service (THEMES_VALIDES) ; {@link NotBlank}
 * évite un NPE sur {@code Set.of(...).contains(null)} si absent.
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteTheme(@NotBlank(message = "Le thème est obligatoire.") String valeur) {}
