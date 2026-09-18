/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps JSON de la requête PATCH /api/admin/config.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteConfig(
    @NotBlank(message = "Clé de configuration invalide.") String cle, String valeur) {}
